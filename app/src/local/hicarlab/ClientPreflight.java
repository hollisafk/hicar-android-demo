package local.hicarlab;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.*;
import android.os.*;

/** Read-only ordinary-app API check; no discovery, group creation or peer data. */
final class ClientPreflight {
    private static boolean checked;
    static synchronized void run(Context context){
        if(checked||Build.VERSION.SDK_INT<29)return;
        Context c=context.getApplicationContext();
        String permission=Build.VERSION.SDK_INT>=33?"android.permission.NEARBY_WIFI_DEVICES":"android.permission.ACCESS_FINE_LOCATION";
        if(c.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){DiagnosticLog.add(c,"加入端能力检查：现有 Wi-Fi 权限未就绪，未执行。");return;}
        checked=true;
        WifiP2pManager manager=(WifiP2pManager)c.getSystemService(Context.WIFI_P2P_SERVICE);
        if(manager==null){DiagnosticLog.add(c,"加入端能力检查：无 Wi-Fi Direct 服务。");return;}
        Handler h=new Handler(Looper.getMainLooper());
        final WifiP2pManager.Channel[] channel=new WifiP2pManager.Channel[1];
        final boolean[] finished={false};
        Runnable finish=()->{if(finished[0])return;finished[0]=true;if(channel[0]!=null)channel[0].close();};
        try{
            channel[0]=manager.initialize(c,Looper.getMainLooper(),()->DiagnosticLog.add(c,"加入端能力检查：接口通道关闭。"));
            if(channel[0]==null){DiagnosticLog.add(c,"加入端能力检查：接口初始化失败。");return;}
            DiagnosticLog.add(c,"加入端只读检查开始：不创建热点、不发现或连接其他设备、不记录地址。");
            manager.requestDeviceInfo(channel[0],device->{
                if(finished[0])return;
                String address=device==null?null:device.deviceAddress;
                boolean usable=address!=null&&address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&!address.equals("02:00:00:00:00:00")&&!address.equals("00:00:00:00:00:00");
                DiagnosticLog.add(c,usable?"加入端检查：公开接口返回非占位本机 P2P 地址；尚未验证可入网。":"加入端检查：公开接口未返回可用本机 P2P 地址；未启动华为建组实验。");
                finish.run();
            });
            h.postDelayed(()->{if(!finished[0]){DiagnosticLog.add(c,"加入端只读检查超时；未启动连接实验。");finish.run();}},10000);
        }catch(RuntimeException e){finish.run();DiagnosticLog.add(c,"加入端只读检查失败："+e.getClass().getSimpleName());}
    }
}
