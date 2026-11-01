package local.hicarlab;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Daily receiver controls. Protocol experiments remain in MainActivity. */
public final class HomeActivity extends ThemeActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,detail,micStatus;
    private Switch micSwitch;
    private Button start,stop,retry;
    private boolean pending;
    private static final int PERMISSIONS=60,ENABLE=61,DISCOVER=62,MICROPHONE=63,NOTIFICATIONS=64;
    private ReceiverEngine engine(){return ((LabApplication)getApplication()).engine();}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private final Runnable update=new Runnable(){public void run(){
        if(status==null)return;
        boolean active=engine().isActive();
        ReceiverStatus.Snapshot state=engine().displayStatus();
        micStatus.setText(engine().microphoneStatus());
        status.setText(state.title);detail.setText(state.detail);retry.setVisibility(active||state.problem?View.VISIBLE:View.GONE);
        start.setText(active?"返回接收画面":"开始连接");
        start.setEnabled(!pending);stop.setEnabled(active);handler.postDelayed(this,700);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(state!=null)pending=state.getBoolean("pending");
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24),dp(36),dp(24),dp(24));root.setBackgroundColor(UiTheme.background(this));scroll.addView(root);
        TextView title=new TextView(this);title.setText("HiCar 接收端");title.setTextSize(30);title.setTextColor(UiTheme.text(this));root.addView(title);
        TextView version=new TextView(this);version.setText("实验版 0.11.0");version.setTextSize(14);version.setPadding(0,dp(8),0,dp(32));root.addView(version);
        status=new TextView(this);status.setTextSize(24);status.setTextColor(UiTheme.accent(this));status.setPadding(dp(18),dp(24),dp(18),dp(24));status.setBackgroundColor(UiTheme.card(this));root.addView(status);
        detail=new TextView(this);detail.setTextSize(15);detail.setPadding(dp(18),dp(12),dp(18),dp(12));root.addView(detail);
        TextView help=new TextView(this);help.setText("开启华为手机的蓝牙和 WLAN，点击下方开始连接。首次连接按手机提示输入接收画面中的六位码。\n\n接收时可锁屏或切换应用；结束时请点停止连接。");help.setTextSize(16);help.setPadding(0,dp(24),0,dp(20));root.addView(help);
        micSwitch=new Switch(this);micSwitch.setText("麦克风语音实验");micSwitch.setChecked(MicrophoneSender.enabled(this));root.addView(micSwitch);
        micStatus=new TextView(this);micStatus.setTextSize(14);micStatus.setPadding(0,dp(8),0,dp(8));root.addView(micStatus);
        micSwitch.setOnCheckedChangeListener((button,checked)->{
            if(checked&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){micSwitch.setChecked(false);requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MICROPHONE);return;}
            getSharedPreferences("ui",0).edit().putBoolean("microphone",checked).apply();
            if(!checked)engine().disableMicrophone();
            else if(engine().isActive())Toast.makeText(this,"重新连接后启用；仅在手机请求语音时采集",Toast.LENGTH_LONG).show();
        });
        Switch autoStart=new Switch(this);autoStart.setText("开机自动接收");autoStart.setChecked(AutoStart.enabled(this));
        autoStart.setPadding(0,dp(16),0,dp(8));root.addView(autoStart);
        TextView autoHelp=new TextView(this);autoHelp.setText("开机后自动等待已配对手机连接；首次请先手动连接。需要在系统中允许自启动及后台运行，保持蓝牙开启。\n自动接收不直接弹出画面，点击接收通知打开；语音功能请手动重新连接启用。休眠唤醒是否可用取决于车机系统。");autoHelp.setTextSize(14);root.addView(autoHelp);
        autoStart.setOnCheckedChangeListener((button,checked)->{
            if(checked){String problem=AutoStart.problem(this);if(problem!=null){autoStart.setChecked(false);Toast.makeText(this,problem,Toast.LENGTH_LONG).show();return;}}
            getSharedPreferences("ui",0).edit().putBoolean("auto_start",checked).apply();
            if(checked&&Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},NOTIFICATIONS);
            Toast.makeText(this,checked?"已启用，下次开机自动等待连接；请在系统中允许自启动":"已关闭开机自动接收；当前连接不受影响",Toast.LENGTH_LONG).show();
        });
        button(root,"试运行自动接收",()->{
            if(!AutoStart.enabled(this)){Toast.makeText(this,"请先开启“开机自动接收”",Toast.LENGTH_LONG).show();return;}
            String problem=AutoStart.problem(this);if(problem!=null){Toast.makeText(this,problem,Toast.LENGTH_LONG).show();return;}
            if(engine().isActive()){Toast.makeText(this,"请先停止当前连接，再试运行自动接收",Toast.LENGTH_LONG).show();return;}
            try{startForegroundService(new Intent(this,ReceiverService.class).setAction("AUTO_START"));Toast.makeText(this,"正在试运行：等待手机连接，点击接收通知打开画面；本轮不启用麦克风",Toast.LENGTH_LONG).show();}
            catch(RuntimeException e){Toast.makeText(this,"系统未允许自动接收，请检查后台运行设置",Toast.LENGTH_LONG).show();}
        });
        start=button(root,"开始连接",()->{if(engine().isActive())openVideo();else{pending=true;continueStart();}});
        retry=button(root,"重新连接",()->{engine().stop(true);pending=true;continueStart();});
        stop=button(root,"停止连接",()->{pending=false;engine().stop(true);status.setText("已停止");});
        button(root,"外观："+UiTheme.label(this),()->UiTheme.choose(this));
        button(root,"设置与诊断",()->startActivity(new Intent(this,MainActivity.class)));
        button(root,"系统后台与通知设置",()->{try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())));}catch(RuntimeException e){Toast.makeText(this,"请在系统设置中打开本应用详情",Toast.LENGTH_LONG).show();}});
        TextView note=new TextView(this);note.setText("请停车后操作。");note.setTextSize(13);note.setPadding(0,dp(24),0,0);root.addView(note);
        setContentView(scroll);
        if(getIntent().getBooleanExtra("retry",false)){getIntent().removeExtra("retry");handler.post(()->{pending=true;continueStart();});}
    }
    private Button button(LinearLayout root,String title,Runnable action){
        Button b=new Button(this);b.setText(title);b.setTextSize(18);b.setMinHeight(dp(56));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);root.addView(b,p);b.setOnClickListener(v->action.run());return b;
    }
    private void message(String value){pending=false;engine().reportProblem(value);Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void continueStart(){
        if(!pending)return;
        if(engine().isActive()){pending=false;openVideo();return;}
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31)for(String p:new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        String wifi=Build.VERSION.SDK_INT>=33?"android.permission.NEARBY_WIFI_DEVICES":Manifest.permission.ACCESS_FINE_LOCATION;
        if(checkSelfPermission(wifi)!=PackageManager.PERMISSION_GRANTED)missing.add(wifi);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),PERMISSIONS);return;}
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){message("这台设备没有可用蓝牙适配器");return;}
        try{
            if(!adapter.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE);return;}
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120),DISCOVER);
        }catch(RuntimeException e){message("系统无法打开蓝牙连接请求，请到设置与诊断查看");}
    }
    private void openVideo(){startActivity(new Intent(this,VideoActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTIFICATIONS){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)Toast.makeText(this,"通知未开启，自动接收时需从应用入口打开画面",Toast.LENGTH_LONG).show();return;}
        if(request==MICROPHONE){
            boolean granted=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            getSharedPreferences("ui",0).edit().putBoolean("microphone",granted).apply();micSwitch.setChecked(granted);
            Toast.makeText(this,granted?"已启用语音实验；连接后在 HiCar 中启动语音":"未启用麦克风，画面和播放不受影响",Toast.LENGTH_LONG).show();return;
        }
        if(request==PERMISSIONS){if(results.length==0){message("已取消权限申请");return;}for(int result:results)if(result!=PackageManager.PERMISSION_GRANTED){message("需要蓝牙及附近设备权限才能开始连接");return;}continueStart();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(!pending)return;
        if(request==ENABLE){if(result==RESULT_OK)continueStart();else message("蓝牙尚未开启");}
        if(request==DISCOVER){
            pending=false;if(result<=0){message("已取消开始连接");return;}
            String model=getSharedPreferences("identity",0).getString("wire_model","00048000");
            if(model==null || !model.matches("[0-9A-Fa-f]{8}")){message("接收设置无效，请到设置与诊断检查");return;}
            try{startForegroundService(new Intent(this,ReceiverService.class).putExtra("model",model).putExtra("network",true).putExtra("video",true));openVideo();}
            catch(RuntimeException e){message("接收服务启动失败，请查看诊断页");}
        }
    }
    @Override protected void onStart(){super.onStart();handler.removeCallbacks(update);handler.post(update);}
    @Override protected void onStop(){handler.removeCallbacks(update);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("pending",pending);super.onSaveInstanceState(state);}
}
