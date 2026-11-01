package local.hicarlab;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())||!AutoStart.enabled(context))return;
        String problem=AutoStart.problem(context);
        if(problem!=null){DiagnosticLog.add(context,"开机自动接收未启动："+problem);return;}
        try{
            context.startForegroundService(new Intent(context,ReceiverService.class).setAction("AUTO_START"));
            DiagnosticLog.add(context,"收到开机完成通知，已请求自动接收；未请求后台弹出界面或开启麦克风。");
        }catch(RuntimeException e){DiagnosticLog.add(context,"系统未允许开机接收服务："+e.getClass().getSimpleName()+"；可手动打开应用连接。");}
    }
}
