package local.hicarlab;
import android.bluetooth.*;
import android.content.*;
import android.provider.Settings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Ordinary app reads only its own adapter metadata. Never changes identity. */
final class LocalIdentityProbe {
    private static boolean checked;
    private static boolean valid(String s){return s!=null&&s.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&!s.equals("02:00:00:00:00:00")&&!s.equals("00:00:00:00:00:00")&&(Integer.parseInt(s.substring(0,2),16)&1)==0;}
    static synchronized void run(Context context){
        if(checked)return;checked=true;Set<String> addresses=new HashSet<>();
        try{BluetoothManager m=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);String a=m.getAdapter().getAddress();if(valid(a))addresses.add(a.toUpperCase(Locale.ROOT));}catch(RuntimeException ignored){}
        try{String a=Settings.Secure.getString(context.getContentResolver(),"bluetooth_address");if(valid(a))addresses.add(a.toUpperCase(Locale.ROOT));}catch(RuntimeException ignored){}
        try{String a=Settings.Global.getString(context.getContentResolver(),"bluetooth_address");if(valid(a))addresses.add(a.toUpperCase(Locale.ROOT));}catch(RuntimeException ignored){}
        try{
            if(addresses.size()==1){
                String model="00048000",address=addresses.iterator().next();
                byte[] hash=MessageDigest.getInstance("SHA-256").digest((model+address.replace(":","")).getBytes(StandardCharsets.US_ASCII));
                StringBuilder candidate=new StringBuilder();for(byte b:hash)candidate.append(String.format(Locale.ROOT,"%02X",b&255));
                String current=context.getSharedPreferences("identity",0).getString("id","");
                DiagnosticLog.add(context,"本机身份只读检查：APK 自行取得唯一蓝牙地址，型号派生身份与现有身份一致 "+candidate.toString().equals(current)+"；未输出地址，未更换身份或信任记录。");Arrays.fill(hash,(byte)0);
            }else DiagnosticLog.add(context,"本机身份只读检查：普通应用未取得唯一可用蓝牙地址；未更换身份或信任记录。");
        }catch(Exception e){DiagnosticLog.add(context,"本机身份只读检查失败："+e.getClass().getSimpleName());}
        addresses.clear();
    }
}
