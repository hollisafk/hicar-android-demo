package local.hicarlab;

import android.content.Context;
import android.net.wifi.*;
import android.os.*;
import android.util.SparseIntArray;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executor;

/** Public AP creation with experimental SystemApi runtime-info callback. */
final class LocalHotspot implements AutoCloseable {
    private final Context context;
    private final P2pHotspot.Events events;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private final Set<String> oldAddresses=new HashSet<>();
    private volatile boolean closed;
    private int attempts;
    private LocalApInfo watch;
    private String scannedBssid,requestedBssid;private int actualFrequency;
    private boolean delivered;
    LocalHotspot(Context c,P2pHotspot.Events e){context=c.getApplicationContext();events=e;}
    private static Set<String> addresses()throws Exception {
        Set<String> result=new HashSet<>();Enumeration<NetworkInterface> networks=NetworkInterface.getNetworkInterfaces();
        while(networks.hasMoreElements())for(InterfaceAddress a:networks.nextElement().getInterfaceAddresses())
            if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()&&a.getNetworkPrefixLength()==24)result.add(a.getAddress().getHostAddress());
        return result;
    }
    void start(){
        if(Build.VERSION.SDK_INT<33){events.failed("本轮本地热点兼容接口需要 Android 13 以上");return;}
        try{
            oldAddresses.addAll(addresses());
            byte[] random=new byte[12];new SecureRandom().nextBytes(random);StringBuilder pass=new StringBuilder();for(byte b:random)pass.append(String.format(Locale.ROOT,"%02x",b&255));
            Class<?> configClass=Class.forName("android.net.wifi.SoftApConfiguration"),builderClass=Class.forName("android.net.wifi.SoftApConfiguration$Builder");
            Object builder=builderClass.getConstructor().newInstance();
            builderClass.getMethod("setSsid",String.class).invoke(builder,"HiCarLab-"+pass.substring(0,8));
            builderClass.getMethod("setPassphrase",String.class,int.class).invoke(builder,pass.toString(),1);
            SparseIntArray channels=new SparseIntArray();channels.put(2,149);
            builderClass.getMethod("setChannels",SparseIntArray.class).invoke(builder,channels);
            Object config=builderClass.getMethod("build").invoke(builder);
            if(Build.VERSION.SDK_INT<36){config=CustomApConfiguration.create("HiCarLab-"+pass.substring(0,8),pass.toString());Object requested=config.getClass().getMethod("getBssid").invoke(config);requestedBssid=requested==null?null:requested.toString();events.log("请求应用自有的本地随机热点地址；是否保留由系统启动回调确认，未使用外部地址。");}
            WifiManager manager=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            events.log(Build.VERSION.SDK_INT>=36?"请求公开接口创建临时本地热点，指定 5 GHz 信道 149；不记录名称或密码。":"尝试本地热点 SystemApi 兼容接口，指定 5 GHz 信道 149；普通调用、不修改权限或系统限制。");
            WifiManager.LocalOnlyHotspotCallback callback=new WifiManager.LocalOnlyHotspotCallback(){
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r){if(closed){r.close();return;}reservation=r;inspect();}
                @Override public void onFailed(int reason){if(!closed)events.failed("本地热点启动失败，系统代码 "+reason);}
                @Override public void onStopped(){if(!closed)events.failed("本地热点被系统停止");}
            };
            Executor executor=command->handler.post(command);
            String method=Build.VERSION.SDK_INT>=36?"startLocalOnlyHotspotWithConfiguration":"startLocalOnlyHotspot";
            WifiManager.class.getMethod(method,configClass,Executor.class,WifiManager.LocalOnlyHotspotCallback.class).invoke(manager,config,executor,callback);
        }catch(Exception e){events.failed("公开本地热点接口失败："+e.getClass().getSimpleName());}
    }
    private void inspect(){
        if(closed||delivered)return;
        try{
            Object config=reservation.getClass().getMethod("getSoftApConfiguration").invoke(reservation);
            Object address=config.getClass().getMethod("getBssid").invoke(config);
            boolean absent = address == null || address.toString().equals("02:00:00:00:00:00");
            String ssid=(String)config.getClass().getMethod("getSsid").invoke(config),password=(String)config.getClass().getMethod("getPassphrase").invoke(config);
            SparseIntArray channels=(SparseIntArray)config.getClass().getMethod("getChannels").invoke(config);
            if(channels.size()!=1||channels.get(2,0)!=149){events.failed("系统未保留指定的 5 GHz 信道配置");return;}
            Set<String> added=addresses();added.removeAll(oldAddresses);
            if(added.size()!=1){if(++attempts<30){handler.postDelayed(this::inspect,500);return;}events.failed("无法唯一确认本轮热点 IPv4 地址");return;}
            String owner=added.iterator().next();int dot=owner.lastIndexOf('.');String client=owner.substring(0,dot+1)+(owner.endsWith(".2")?"3":"2");
            if(requestedBssid!=null&&(absent||!requestedBssid.equalsIgnoreCase(address.toString()))){events.failed("系统未保留本轮请求的热点地址；未发送配置。");return;}
            if(scannedBssid==null){
                if(watch==null){
                    watch=new LocalApInfo(context,new LocalApInfo.Events(){
                        public void found(String bssid,int frequency){if(!closed){scannedBssid=bssid;actualFrequency=frequency;inspect();}}
                        public void failed(String reason){if(!closed)events.failed(reason);}
                        public void log(String text){events.log(text);}
                    },absent?null:address.toString());watch.start();
                }
                return;
            }
            NetworkResource.Offer offer = new NetworkResource.Offer(ssid,absent?scannedBssid:address.toString(),password,owner,client,actualFrequency,4005);
            events.log("本轮热点配置与实际频道已就绪；无 USB 地址输入，开始蓝牙网络连接验证。");
            delivered=true; events.ready(offer);
        }catch(Exception e){events.failed("本地热点参数检查失败："+e.getClass().getSimpleName());}
    }
    @Override public void close(){closed=true;handler.removeCallbacksAndMessages(null);if(watch!=null){watch.close();watch=null;}if(reservation!=null){reservation.close();reservation=null;events.log("本轮本地热点保留请求已释放。");}}
}
