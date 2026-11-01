package local.hicarlab;
import android.content.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Experimental SystemApi call with existing Nearby permission, no exemptions. */
final class LocalApInfo implements AutoCloseable {
    interface Events {void found(String address,int frequency);void failed(String reason);void log(String text);}
    private final Context context; private final Events events;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WifiManager wifi; private Object callback; private Class<?> callbackClass;
    private final String configuredAddress;
    private boolean closed,registered;
    private final Set<String> logged=new HashSet<>();
    LocalApInfo(Context c,Events e){this(c,e,null);}
    LocalApInfo(Context c,Events e,String address){context=c.getApplicationContext();events=e;configuredAddress=address;}
    void start(){
        try{
            wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            callbackClass=Class.forName("android.net.wifi.WifiManager$SoftApCallback");
            callback=Proxy.newProxyInstance(context.getClassLoader(),new Class<?>[]{callbackClass},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class){
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return args!=null&&args.length==1&&proxy==args[0];
                    return "HiCarLabLocalApCallback";
                }
                if(!closed&&logged.add("callback:"+method.getName()))events.log("热点状态回调事件："+method.getName()+"，参数个数 "+(args==null?0:args.length)+"（不记录参数内容）。");
                if(!closed&&method.getName().equals("onInfoChanged")&&args!=null&&args.length==1){
                    if(args[0] instanceof List) inspect((List<?>)args[0]);
                    else if(args[0]!=null)inspect(Collections.singletonList(args[0]));
                }
                return null;
            });
            Executor executor=command->handler.post(command);
            WifiManager.class.getMethod("registerLocalOnlyHotspotSoftApCallback",Executor.class,callbackClass).invoke(wifi,executor,callback);
            registered=true;events.log("本地热点 SystemApi 状态回调注册调用返回；等待本轮运行信息，不代表已取得地址。");
            handler.postDelayed(()->{if(!closed){close();events.failed("热点状态回调未提供可核验的唯一地址；未发送配置。");}},15000);
        }catch(ReflectiveOperationException|RuntimeException e){close();Throwable t=e instanceof InvocationTargetException?((InvocationTargetException)e).getCause():e;events.failed("普通应用热点状态回调调用失败："+(t==null?e:t).getClass().getSimpleName()+"；未修改权限或系统限制。");}
    }
    private void inspect(List<?> infos){
        if(closed)return;
        if(logged.add("count:"+infos.size()))events.log("本轮热点状态实例数量："+infos.size()+"。");
        if(infos.isEmpty())return;
        try{
            if(infos.size()!=1){close();events.failed("热点运行信息包含多个实例，未选择地址。");return;}
            Object info=infos.get(0);
            int frequency=(Integer)info.getClass().getMethod("getFrequency").invoke(info);
            Object mac=info.getClass().getMethod("getBssid").invoke(info);
            if(logged.add("info:"+frequency+":"+(mac!=null)))events.log("本轮热点状态：频率 "+frequency+" MHz，地址字段存在 "+(mac!=null)+"（值不写日志）。");
            if(NetworkResource.frequencyMask(frequency)==0)return;
            if(mac==null&&configuredAddress==null)return;
            String address=mac!=null?mac.toString():configuredAddress;
            if(mac==null)events.log("使用系统启动回调保留的本轮配置地址；实际连接仍待手机验证，运行回调已确认频道。");
            if(mac!=null&&configuredAddress!=null&&!address.equalsIgnoreCase(configuredAddress)){close();events.failed("系统配置地址与运行地址不一致；未发送配置。");return;}
            if(!address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")||address.equals("02:00:00:00:00:00")||address.equals("00:00:00:00:00:00")||(Integer.parseInt(address.substring(0,2),16)&1)!=0)return;
            close();events.log("本地热点运行回调提供唯一的 "+frequency+" MHz 实例信息（地址值不写日志）。");events.found(address,frequency);
        }catch(ReflectiveOperationException|RuntimeException e){close();events.failed("热点运行信息读取失败："+e.getClass().getSimpleName());}
    }
    public void close(){
        if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);
        if(registered){registered=false;try{WifiManager.class.getMethod("unregisterLocalOnlyHotspotSoftApCallback",callbackClass).invoke(wifi,callback);}catch(ReflectiveOperationException|RuntimeException e){events.log("热点状态回调注销未确认："+e.getClass().getSimpleName());}}
    }
}
