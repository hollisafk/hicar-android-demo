package local.hicarlab;

import android.content.*;
import android.net.wifi.p2p.*;
import android.os.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

/** Owns only the temporary group created by this experiment. */
final class P2pHotspot implements AutoCloseable {
    interface Events {void ready(NetworkResource.Offer offer);void failed(String reason);void log(String message);}
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private final Events events;
    private volatile boolean closed,created;
    private int attempts;
    P2pHotspot(Context c,Events events){context=c.getApplicationContext();this.events=events;manager=(WifiP2pManager)c.getSystemService(Context.WIFI_P2P_SERVICE);}
    void start(){
        if(Build.VERSION.SDK_INT<29||manager==null){events.failed("系统未提供所需 Wi-Fi Direct 接口");return;}
        try{
            channel=manager.initialize(context,Looper.getMainLooper(),()->{if(!closed)events.failed("Wi-Fi Direct 服务断开");});
            if(channel==null){events.failed("Wi-Fi Direct 初始化失败");return;}
            manager.requestGroupInfo(channel,existing->{
                if(closed)return;
                if(existing!=null){events.failed("已有 Wi-Fi Direct 连接，实验未修改它");return;}
                create();
            });
        }catch(RuntimeException e){events.failed("Wi-Fi Direct 接口拒绝："+e.getClass().getSimpleName());}
    }
    private void create(){
        try{
            byte[] random=new byte[12];new SecureRandom().nextBytes(random);StringBuilder password=new StringBuilder();for(byte b:random)password.append(String.format(Locale.ROOT,"%02x",b&255));
            WifiP2pConfig config=new WifiP2pConfig.Builder().setNetworkName("DIRECT-HLHiCarLab-"+password.substring(0,4))
                .setPassphrase(password.toString()).setGroupOperatingFrequency(5745).enablePersistentMode(false).build();
            events.log("正在创建本轮临时 Wi-Fi Direct 热点（5 GHz 信道 149）；不记录密码。");
            manager.createGroup(channel,config,new WifiP2pManager.ActionListener(){
                public void onSuccess(){created=true;if(closed){remove();return;}poll();}
                public void onFailure(int reason){if(!closed)events.failed("临时热点创建失败，系统代码 "+reason);}
            });
        }catch(RuntimeException e){events.failed("热点配置失败："+e.getClass().getSimpleName());}
    }
    private void poll(){
        if(closed)return;
        manager.requestGroupInfo(channel,group->{
            if(closed)return;
            if(group==null){if(++attempts<30)handler.postDelayed(this::poll,500);else events.failed("临时热点就绪超时");return;}
            if(!group.isGroupOwner()){events.failed("未成为本轮热点所有者");return;}
            try{
                String bssid=null;
                if(Build.VERSION.SDK_INT>=36){
                    try{Object address=group.getClass().getMethod("getGroupOwnerBssid").invoke(group);if(address!=null)bssid=address.toString();}
                    catch(ReflectiveOperationException | RuntimeException ignored){}
                }
                String owner=null;NetworkInterface network=NetworkInterface.getByName(group.getInterface());
                if(network!=null)for(InterfaceAddress a:network.getInterfaceAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()&&a.getNetworkPrefixLength()==24)owner=a.getAddress().getHostAddress();
                if(owner==null){if(++attempts<30){handler.postDelayed(this::poll,500);return;}events.failed("未取得本轮热点真实 IPv4 地址");return;}
                if(!usableMac(bssid))bssid=OwnGroupAddress.read(network,group.getInterface(),events);
                if(!usableMac(bssid)){events.failed("系统未向普通应用提供本轮热点 BSSID；未发送占位地址");return;}
                int dot=owner.lastIndexOf('.');String client=owner.substring(0,dot+1)+(owner.endsWith(".2")?"3":"2");
                NetworkResource.Offer offer=new NetworkResource.Offer(group.getNetworkName(),bssid,group.getPassphrase(),owner,client,group.getFrequency(),4005);
                events.log("临时热点已就绪：频率 "+group.getFrequency()+" MHz，真实 BSSID 与 IPv4 已取得（值不写日志）。");
                events.ready(offer);
            }catch(Exception e){events.failed("热点参数核验失败："+e.getClass().getSimpleName());}
        });
    }
    private static boolean usableMac(String value){
        return value!=null&&value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&!value.equals("02:00:00:00:00:00")&&!value.equals("00:00:00:00:00:00");
    }
    private void remove(){if(created&&channel!=null){created=false;manager.removeGroup(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){events.log("本轮临时热点已移除。");}public void onFailure(int reason){events.log("临时热点移除未确认，系统代码 "+reason);}});}}
    @Override public void close(){closed=true;handler.removeCallbacksAndMessages(null);remove();}
}
