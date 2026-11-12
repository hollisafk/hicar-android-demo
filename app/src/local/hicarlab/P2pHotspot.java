package local.hicarlab;

import android.content.*;
import android.net.wifi.*;
import android.net.wifi.p2p.*;
import android.os.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/** Owns only the temporary group created by this experiment.
 *  API 29+ : WifiP2pConfig.Builder (explicit SSID / passphrase / 5 GHz channel 149).
 *  API 22-28: legacy two-arg createGroup(); the system generates SSID / passphrase and we
 *             read them back from WifiP2pGroup. The 5 GHz operating channel is requested
 *             through the platform's setWifiP2pChannels() before creating the group, because
 *             WifiP2pGroup.getFrequency() only exists from API 29. */
final class P2pHotspot implements AutoCloseable {
    interface Events {void ready(NetworkResource.Offer offer);void failed(String reason);void log(String message);}
    private static final int CHANNEL_149=149, FREQ_5745=5745;
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private final Events events;
    private volatile boolean closed,created;
    private int attempts,scanTries;
    private String scannedBssid;
    private int legacyFrequency;
    private boolean channelChanged;
    P2pHotspot(Context c,Events events){context=c.getApplicationContext();this.events=events;manager=(WifiP2pManager)c.getSystemService(Context.WIFI_P2P_SERVICE);}
    void start(){
        if(manager==null){events.failed("系统未提供所需 Wi-Fi Direct 接口");return;}
        if(Build.VERSION.SDK_INT<29){
            WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if(wifi!=null&&!wifi.isWifiEnabled()){
                try{wifi.setWifiEnabled(true);events.log("Wi-Fi 未开启，已请求系统开启以启用 Wi-Fi Direct。");}
                catch(RuntimeException e){events.log("请求开启 Wi-Fi 未获系统允许："+e.getClass().getSimpleName());}
                waitWifiReady(0);return;
            }
        }
        initializeAndCreate();
    }
    /** setWifiEnabled 是异步的：必须等 Wi-Fi 与 P2P 服务真正就绪再 initialize / createGroup。 */
    private void waitWifiReady(int tries){
        if(closed)return;
        WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        boolean on=wifi!=null&&wifi.isWifiEnabled();
        if(!on&&tries<20){handler.postDelayed(()->waitWifiReady(tries+1),500);return;}
        if(!on){events.failed("Wi-Fi 未能开启，请到系统设置中打开 Wi-Fi 后重试");return;}
        events.log("Wi-Fi 已开启，等待 Wi-Fi Direct 服务就绪。");
        handler.postDelayed(this::initializeAndCreate,1000);
    }
    private void initializeAndCreate(){
        if(closed)return;
        try{
            channel=manager.initialize(context,Looper.getMainLooper(),()->{if(!closed)events.failed("Wi-Fi Direct 服务断开");});
            if(channel==null){events.failed("Wi-Fi Direct 初始化失败");return;}
            manager.requestGroupInfo(channel,existing->{
                if(closed)return;
                if(existing!=null){
                    // 上轮实验异常退出可能遗留 P2P 组：先移除再重建，避免用户反复重试都被挡住。
                    events.log("检测到上轮遗留的 Wi-Fi Direct 组，先移除再重建。");
                    manager.removeGroup(channel,new WifiP2pManager.ActionListener(){
                        public void onSuccess(){if(!closed)handler.postDelayed(() -> create(),600);}
                        public void onFailure(int reason){if(!closed)events.failed("已有 Wi-Fi Direct 连接且无法移除，系统代码 "+reason);}
                    });
                    return;
                }
                create();
            });
        }catch(RuntimeException e){events.failed("Wi-Fi Direct 接口拒绝："+e.getClass().getSimpleName());}
    }
    private void create(){
        if(Build.VERSION.SDK_INT<29){createLegacy();return;}
        try{
            WifiP2pManager.ActionListener listener=new WifiP2pManager.ActionListener(){
                public void onSuccess(){created=true;if(closed){remove();return;}poll();}
                public void onFailure(int reason){if(!closed)events.failed("临时热点创建失败，系统代码 "+reason);}
            };
            // 反射隔离：低版本加载本类时不会解析 Builder / 三参 createGroup。
            Class<?> helper=Class.forName("local.hicarlab.P2pGroupV29");
            helper.getMethod("create",WifiP2pManager.class,WifiP2pManager.Channel.class,
                    P2pHotspot.Events.class,WifiP2pManager.ActionListener.class)
                    .invoke(null,manager,channel,events,listener);
        }catch(RuntimeException | ReflectiveOperationException e){
            Throwable t=e instanceof InvocationTargetException?((InvocationTargetException)e).getCause():e;
            events.failed("热点配置失败："+(t==null?e:t).getClass().getSimpleName());
        }
    }
    /** API 22-28：先把 P2P operating channel 固定到 149（5 GHz / 5745 MHz），再让系统自建组。 */
    private void createLegacy(){
        try{
            Method set=WifiP2pManager.class.getMethod("setWifiP2pChannels",
                    WifiP2pManager.Channel.class,int.class,int.class,WifiP2pManager.ActionListener.class);
            set.invoke(manager,channel,1,CHANNEL_149,new WifiP2pManager.ActionListener(){
                public void onSuccess(){legacyFrequency=FREQ_5745;channelChanged=true;
                    events.log("系统已确认本轮 Wi-Fi Direct 使用 5 GHz 信道 149（5745 MHz）。");doCreateLegacy();}
                public void onFailure(int reason){events.log("系统未接受指定 5 GHz 信道（代码 "+reason+"），改由系统自选频段。");doCreateLegacy();}
            });
        }catch(ReflectiveOperationException | RuntimeException e){
            events.log("本系统未提供 P2P 信道设置接口，改由系统自选频段。");doCreateLegacy();
        }
    }
    private void doCreateLegacy(){
        try{
            events.log("正在创建本轮临时 Wi-Fi Direct 热点（系统兼容接口，名称与密码由系统生成后回读）。");
            manager.createGroup(channel,new WifiP2pManager.ActionListener(){
                public void onSuccess(){created=true;if(closed){remove();return;}poll();}
                public void onFailure(int reason){if(!closed)events.failed("临时热点创建失败，系统代码 "+reason+"（请确认 Wi-Fi 已开启且未被系统设置禁用）");}
            });
        }catch(RuntimeException e){events.failed("系统兼容接口创建热点失败："+e.getClass().getSimpleName());}
    }
    private void poll(){
        if(closed)return;
        manager.requestGroupInfo(channel,group->{
            if(closed)return;
            if(group==null){if(++attempts<30)handler.postDelayed(this::poll,500);else events.failed("临时热点就绪超时");return;}
            if(!group.isGroupOwner()){events.failed("未成为本轮热点所有者");return;}
            try{
                int frequency=groupFrequency(group);
                if(NetworkResource.frequencyMask(frequency)==0){
                    // 低版本没有 getFrequency()：反复请求扫描并等待结果刷新（最多约 10 秒）。
                    if(Build.VERSION.SDK_INT<29&&scanTries<12){scanTries++;if(scanTries%3==1)requestScan();handler.postDelayed(this::poll,800);return;}
                    events.failed("本轮热点频率为 "+(frequency>0?frequency+" MHz":"未知（扫描未匹配）")+"；HiCar 需要 5 GHz 指定信道（5180/5200/5220/5240/5745/5765/5785/5805/5825），当前系统把热点放在了不支持的频段。");
                    return;
                }
                String bssid=null;
                if(Build.VERSION.SDK_INT>=36){
                    try{Object address=group.getClass().getMethod("getGroupOwnerBssid").invoke(group);if(address!=null)bssid=address.toString();}
                    catch(ReflectiveOperationException | RuntimeException ignored){}
                }
                String owner=null;NetworkInterface network=NetworkInterface.getByName(group.getInterface());
                if(network!=null)for(InterfaceAddress a:network.getInterfaceAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()&&a.getNetworkPrefixLength()==24)owner=a.getAddress().getHostAddress();
                if(owner==null){if(++attempts<30){handler.postDelayed(this::poll,500);return;}events.failed("未取得本轮热点真实 IPv4 地址");return;}
                if(!usableMac(bssid))bssid=OwnGroupAddress.read(network,group.getInterface(),events);
                if(!usableMac(bssid)&&usableMac(scannedBssid)){bssid=scannedBssid;events.log("采用本轮扫描结果核验的热点地址；连接仍待手机验证。");}
                if(!usableMac(bssid)){events.failed("系统未向普通应用提供本轮热点 BSSID；未发送占位地址");return;}
                int dot=owner.lastIndexOf('.');String client=owner.substring(0,dot+1)+(owner.endsWith(".2")?"3":"2");
                NetworkResource.Offer offer=new NetworkResource.Offer(group.getNetworkName(),bssid,group.getPassphrase(),owner,client,frequency,4005);
                events.log("临时热点已就绪：频率 "+frequency+" MHz，真实 BSSID 与 IPv4 已取得（值不写日志）。");
                events.ready(offer);
            }catch(Exception e){events.failed("热点参数核验失败："+e.getClass().getSimpleName());}
        });
    }
    /** API 29+ 直接读；低版本采信系统确认的指定信道，扫描结果可用时交叉核验。 */
    private int groupFrequency(WifiP2pGroup group){
        try{return (Integer)group.getClass().getMethod("getFrequency").invoke(group);}
        catch(ReflectiveOperationException | RuntimeException e){
            int scanned=scanFrequency(group.getNetworkName());
            if(scanned>0)return scanned;
            return legacyFrequency;
        }
    }
    private int scanFrequency(String ssid){
        try{
            WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if(wifi==null||ssid==null)return 0;
            List<ScanResult> results=wifi.getScanResults();
            int frequency=0,level=-200;String best=null;
            if(results!=null)for(ScanResult r:results){
                if(r==null||r.SSID==null)continue;
                if(!ssid.equals(r.SSID.replace("\"",""))||r.level<=level)continue;
                level=r.level;frequency=r.frequency;best=r.BSSID;
            }
            if(usableMac(best))scannedBssid=best;
            if(frequency>0)events.log("本轮热点扫描核验：频率 "+frequency+" MHz（候选 "+(results==null?0:results.size())+" 个网络）。");
            return frequency;
        }catch(RuntimeException e){return 0;}
    }
    private void requestScan(){
        try{
            WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            if(wifi!=null)wifi.startScan();
            events.log("已请求一次 Wi-Fi 扫描以核验本轮热点频段；正在等待结果。");
        }catch(RuntimeException e){events.log("请求 Wi-Fi 扫描未获允许："+e.getClass().getSimpleName());}
    }
    private static boolean usableMac(String value){
        return value!=null&&value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&!value.equals("02:00:00:00:00:00")&&!value.equals("00:00:00:00:00:00");
    }
    private void remove(){if(created&&channel!=null){created=false;manager.removeGroup(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){events.log("本轮临时热点已移除。");}public void onFailure(int reason){events.log("临时热点移除未确认，系统代码 "+reason);}});}}
    /** Best-effort 恢复系统默认 P2P 信道（0,0 = 自动），仅在确实改过设置时尝试。 */
    private void restoreChannels(){
        if(!channelChanged||channel==null)return;
        channelChanged=false;
        try{
            Method set=WifiP2pManager.class.getMethod("setWifiP2pChannels",
                    WifiP2pManager.Channel.class,int.class,int.class,WifiP2pManager.ActionListener.class);
            set.invoke(manager,channel,0,0,new WifiP2pManager.ActionListener(){
                public void onSuccess(){events.log("已恢复系统默认 P2P 信道设置。");}
                public void onFailure(int reason){events.log("恢复默认 P2P 信道未获系统确认（代码 "+reason+"），可到系统设置检查。");}
            });
        }catch(ReflectiveOperationException | RuntimeException ignored){}
    }
    @Override public void close(){closed=true;handler.removeCallbacksAndMessages(null);remove();restoreChannels();}
}
