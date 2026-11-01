package local.hicarlab;
import android.content.Context;
import android.net.MacAddress;
import android.os.Build;
import android.util.SparseIntArray;
import java.security.SecureRandom;

/** Constructs an app-owned AP config only. Does not read device/network MACs. */
final class CustomApConfiguration {
    private static boolean probed;
    static Object create(String ssid,String password)throws Exception {
        Class<?> cls=Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object builder=cls.getConstructor().newInstance();
        cls.getMethod("setSsid",String.class).invoke(builder,ssid);
        cls.getMethod("setPassphrase",String.class,int.class).invoke(builder,password,1);
        SparseIntArray channels=new SparseIntArray();channels.put(2,149);
        cls.getMethod("setChannels",SparseIntArray.class).invoke(builder,channels);
        byte[] mac=new byte[6];new SecureRandom().nextBytes(mac);mac[0]=(byte)((mac[0]&0xfc)|2);
        cls.getMethod("setBssid",MacAddress.class).invoke(builder,MacAddress.fromBytes(mac));
        // If the platform disallows this API, stop; no field edits, exemptions,
        // parcel manipulation or privileged permission grants are attempted.
        cls.getMethod("setMacRandomizationSetting",int.class).invoke(builder,0);
        return cls.getMethod("build").invoke(builder);
    }
    static synchronized void probe(Context context) {
        if(probed)return;probed=true;
        if(Build.VERSION.SDK_INT<36){DiagnosticLog.add(context,"自定义热点地址接口检查：本系统版本不适用，未执行。");return;}
        try {
            create("HiCarLab-Configuration-Test","temporary-configuration-only");
            DiagnosticLog.add(context,"自定义热点地址的配置对象构造成功；尚未启动热点，尚未验证驱动采用该地址。");
        }catch(Exception e){DiagnosticLog.add(context,"普通应用无法完整构造指定地址的热点配置："+e.getClass().getSimpleName()+"。未启动热点，未修改系统权限。");}
    }
}
