package local.hicarlab;
import android.app.*;
import android.content.*;
import android.os.*;

public final class ReceiverService extends Service {
    private ReceiverEngine engine;
    private final Handler bootHandler=new Handler(Looper.getMainLooper());
    private boolean waitingForBoot;
    private long bootDeadline;
    private final Runnable bootStart=new Runnable(){public void run(){
        if(!waitingForBoot)return;
        if(!AutoStart.enabled(ReceiverService.this)){waitingForBoot=false;stopSelf();return;}
        if(engine.isActive()){waitingForBoot=false;return;}
        String problem=AutoStart.problem(ReceiverService.this);
        if(problem!=null){waitingForBoot=false;DiagnosticLog.add(ReceiverService.this,"开机自动接收取消："+problem);stopSelf();return;}
        if(!AutoStart.bluetoothReady(ReceiverService.this)){
            if(SystemClock.elapsedRealtime()<bootDeadline){bootHandler.postDelayed(this,2000);return;}
            waitingForBoot=false;DiagnosticLog.add(ReceiverService.this,"开机等待蓝牙超时；未自动开启蓝牙，可手动连接。");stopSelf();return;
        }
        waitingForBoot=false;
        try{
            if(!((LabApplication)getApplication()).video().prepareBackground())throw new IllegalStateException("decoder unavailable");
            String model=getSharedPreferences("identity",0).getString("wire_model","00048000");
            DiagnosticLog.add(ReceiverService.this,"开机自动接收开始：仅等待已配对手机，未请求蓝牙可发现弹窗；本轮麦克风保持关闭。");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(31,notification(true));
            engine.start(model,true,true,false);
        }catch(RuntimeException e){DiagnosticLog.add(ReceiverService.this,"开机自动接收失败："+e.getClass().getSimpleName());((LabApplication)getApplication()).video().close();stopSelf();}
    }};
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("lab-test","HiCar 连接实验",NotificationManager.IMPORTANCE_LOW));
        if(android.os.Build.VERSION.SDK_INT>=29)startForeground(31,notification(false),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(31,notification(false));
        engine=((LabApplication)getApplication()).engine();
        engine.setOnStopped(()->stopSelf());
    }
    private Notification notification(boolean continuous){
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,continuous?VideoActivity.class:HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,ReceiverService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"lab-test")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("HiCar 接收服务")
            .setContentText(continuous ? "持续接收；点击恢复画面，可点停止结束。" : "120秒连接测试；可点停止结束。")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null&&"AUTO_START".equals(intent.getAction())){
            if(!AutoStart.enabled(this)){if(!engine.isActive())stopSelf();return START_NOT_STICKY;}
            if(engine.isActive()||waitingForBoot)return START_NOT_STICKY;
            waitingForBoot=true;bootDeadline=SystemClock.elapsedRealtime()+120000L;
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(31,notification(true));
            bootHandler.post(bootStart);return START_NOT_STICKY;
        }
        waitingForBoot=false;bootHandler.removeCallbacks(bootStart);
        if(intent==null||"STOP".equals(intent.getAction())){DiagnosticLog.add(this,"服务收到停止指令或空指令。");engine.stop(true);stopSelf();return START_NOT_STICKY;}
        String model=intent.getStringExtra("model");
        if(model==null||!model.matches("[0-9a-fA-F]{8}")){stopSelf();return START_NOT_STICKY;}
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(31,notification(intent.getBooleanExtra("video",false)));
        boolean microphone=intent.getBooleanExtra("video",false)&&MicrophoneSender.enabled(this);
        if(microphone && android.os.Build.VERSION.SDK_INT>=29)try{
            startForeground(31,notification(true),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }catch(RuntimeException e){microphone=false;DiagnosticLog.add(this,"麦克风前台服务不可用："+e.getClass().getSimpleName()+"；仅接收画面和播放。");}
        try {engine.start(model, intent.getBooleanExtra("network", false),intent.getBooleanExtra("video",false),microphone);}catch(RuntimeException e){DiagnosticLog.add(this,"测试服务启动失败："+e.getClass().getSimpleName());engine.stop(true);engine.reportProblem("接收服务启动失败，请重新连接或查看诊断页。");stopSelf();}
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){waitingForBoot=false;bootHandler.removeCallbacksAndMessages(null);if(engine!=null){if(engine.isRunning())DiagnosticLog.add(this,"服务被销毁，本轮提前结束。");engine.stop(false);}((LabApplication)getApplication()).video().close();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
