package local.hicarlab;
import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** User-opened video/input test window. The PIN is never included in logs. */
public final class VideoActivity extends ThemeActivity implements TextureView.SurfaceTextureListener {
    private VideoSink sink;
    private InputRouter input;private int pointer=-1;
    private TextureView surface;private boolean foreground;
    private LinearLayout root,codeRow;private TextView statusLine,micStatus,codeTitleView,line1View;
    private boolean fullscreen,landscape,detailsExpanded,receiving,pending;
    private final TextView[] digitViews=new TextView[6];
    private static final int PERMISSIONS=70,ENABLE=71,DISCOVER=72;
    private final android.os.Handler uiHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private LinearLayout notice;
    private ReceiverEngine engine(){return ((LabApplication)getApplication()).engine();}
    private final Runnable updateStatus=new Runnable(){public void run(){
        ReceiverStatus.Snapshot state=engine().displayStatus();
        receiving=state.receiving;
        micStatus.setText(engine().microphoneStatus());
        renderPin();
        statusLine.setText(state.title + (state.detail!=null && !state.detail.isEmpty() ? " · "+state.detail : ""));
        statusLine.setTextColor(state.problem ? 0xffe0511d : 0xff7e8492);
        notice.setVisibility(state.receiving?View.GONE:View.VISIBLE);
        uiHandler.postDelayed(this,700);
    }};
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    /** Scale the connection-code screen to the device so it fits small phones and large panels alike. */
    private void applyCodeLayout(){
        android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
        int sw=dm.widthPixels, sh=dm.heightPixels;
        int maxByW=(int)(sw*0.92/7.2);
        int maxByH=(int)(sh*0.50/1.34);
        int boxW=Math.max(dp(40),Math.min(dp(130),Math.min(maxByW,maxByH)));
        int boxH=(int)(boxW*1.34f);
        int gap=(int)(boxW*0.22f);
        for(int i=0;i<6;i++){
            TextView d=digitViews[i];
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(boxW,boxH);
            lp.setMargins(gap/2,0,gap/2,0);
            d.setLayoutParams(lp);
            d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, boxW*0.46f);
        }
        if(codeTitleView!=null)codeTitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(dp(16),Math.min(dp(34),boxW*0.42f)));
        if(line1View!=null)line1View.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(dp(12),Math.min(dp(20),boxW*0.27f)));
        if(statusLine!=null)statusLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(dp(12),Math.min(dp(18),boxW*0.24f)));
        if(micStatus!=null)micStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.max(dp(11),Math.min(dp(15),boxW*0.20f)));
    }
    private void renderPin(){
        String pin=getSharedPreferences("identity",0).getString("pin","");
        for(int i=0;i<6;i++){
            String d=(pin!=null && i<pin.length())?String.valueOf(pin.charAt(i)):"";
            digitViews[i].setText(d);
        }
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        // 进入主页前必须已具备本机蓝牙地址（自动探测或已保存）。获取不到则转入测试页让用户填写，
        // 避免在缺少身份的情况下空跑接收、且免输码重连因身份缺失而失败。
        String mac = WireIdentity.resolveMac(this);
        if (mac == null) {
            DiagnosticLog.add(this, "本机蓝牙地址为空：自动探测与已保存地址均未取得。转入测试页设置蓝牙地址后再进入主页。");
            Intent gate = new Intent(this, MainActivity.class);
            gate.putExtra("need_bt_address", true);
            startActivity(gate);
            finish();
            return;
        }
        // Make the window ignore system-bar insets so content can extend edge-to-edge (fix MIUI left/right letterbox).
        if(android.os.Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if(android.os.Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(lp);}
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        sink=((LabApplication)getApplication()).video();input=((LabApplication)getApplication()).input();
        if(state!=null){fullscreen=state.getBoolean("fullscreen");landscape=state.getBoolean("landscape");detailsExpanded=state.getBoolean("detailsExpanded");}
        fullscreen=true; landscape=true; // car screen: always fullscreen landscape, no buttons
        receiving=engine().displayStatus().receiving;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff0a0a0f);
        micStatus=new TextView(this);micStatus.setTextSize(13);micStatus.setTextColor(0xff4d8bff);micStatus.setPadding(dp(12),0,dp(12),dp(4));
        surface=new TextureView(this);
        surface.setOnTouchListener((v,event)->touch(v,event));surface.setSurfaceTextureListener(this);
        FrameLayout area=new FrameLayout(this);area.setBackgroundColor(0xff0a0a0f);area.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));

        // ---- HiCar-style pairing / connection-code screen (no buttons at all) ----
        notice=new LinearLayout(this);notice.setOrientation(LinearLayout.VERTICAL);notice.setGravity(Gravity.CENTER);notice.setBackgroundColor(0xff0a0a0f);notice.setPadding(dp(32),dp(24),dp(32),dp(24));notice.setClickable(true);
        codeTitleView=new TextView(this);codeTitleView.setText("HUAWEI HiCar 连接码");codeTitleView.setTextSize(22);codeTitleView.setTypeface(Typeface.DEFAULT_BOLD);codeTitleView.setTextColor(0xffffffff);codeTitleView.setGravity(Gravity.CENTER);
        notice.addView(codeTitleView,new LinearLayout.LayoutParams(-1,-2));
        codeRow=new LinearLayout(this);codeRow.setOrientation(LinearLayout.HORIZONTAL);codeRow.setGravity(Gravity.CENTER);codeRow.setPadding(0,dp(24),0,dp(24));
        for(int i=0;i<6;i++){
            TextView d=new TextView(this);d.setGravity(Gravity.CENTER);d.setTextSize(34);d.setTypeface(Typeface.DEFAULT_BOLD);d.setTextColor(0xffffffff);
            GradientDrawable cd=new GradientDrawable();cd.setColor(0xff2b2f38);cd.setCornerRadius(dp(14));d.setBackground(cd);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(46),dp(62));lp.setMargins(dp(5),0,dp(5),0);
            codeRow.addView(d,lp);digitViews[i]=d;
        }
        notice.addView(codeRow);
        line1View=new TextView(this);line1View.setText("手机开启蓝牙靠近屏幕附近，发现汽车后输入连接码，进行首次验证");line1View.setTextSize(14);line1View.setTextColor(0xffe8edf5);line1View.setGravity(Gravity.CENTER);line1View.setPadding(0,dp(8),0,dp(8));
        notice.addView(line1View,new LinearLayout.LayoutParams(-1,-2));
        statusLine=new TextView(this);statusLine.setTextSize(13);statusLine.setTextColor(0xff7e8492);statusLine.setGravity(Gravity.CENTER);statusLine.setPadding(0,dp(12),0,dp(12));
        notice.addView(statusLine,new LinearLayout.LayoutParams(-1,-2));
        area.addView(notice,new FrameLayout.LayoutParams(-1,-1));

        root.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(fullscreen){v.setPadding(0,0,0,0);return insets;}
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets edge=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(edge.left,edge.top,edge.right,edge.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        applyCodeLayout();setContentView(root);applyOrientation();setFullscreen(true);renderPin();
        // Auto-start the wireless connection as soon as this screen opens.
        if(!engine().isActive()){pending=true;beginConnect();}
    }
    private void beginConnect(){
        if(!pending)return;
        if(engine().isActive()){pending=false;return;}
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=23){ // 5.x 无运行时权限，安装时已静态授予
            if(Build.VERSION.SDK_INT>=31)for(String p:new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
            String wifi=Build.VERSION.SDK_INT>=33?"android.permission.NEARBY_WIFI_DEVICES":Manifest.permission.ACCESS_FINE_LOCATION;
            if(checkSelfPermission(wifi)!=PackageManager.PERMISSION_GRANTED)missing.add(wifi);
            if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),PERMISSIONS);return;}
        }
        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){statusLine.setText("此设备没有可用蓝牙适配器");return;}
        try{
            if(!adapter.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE);return;}
            /* 已配对手机回连不请求可发现态，避免手机弹「靠近发现」；仅首次配对请求。 */
            if(getSharedPreferences("identity",0).getString("peer_mac",null)!=null){startReceiver();return;}
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120),DISCOVER);
        }catch(RuntimeException e){statusLine.setText("系统无法打开蓝牙连接请求，请到设置查看");}
    }
    /** 启动接收服务（已配对手机直接回连，或首次配对完成可发现授权后调用）。 */
    private void startReceiver(){
        pending=false;
        String model=getSharedPreferences("identity",0).getString("wire_model","00048000");
        if(model==null||!model.matches("[0-9A-Fa-f]{8}")){statusLine.setText("接收设置无效，请到设置与诊断检查");return;}
        try{startServiceCompat(new Intent(this,ReceiverService.class).putExtra("model",model).putExtra("network",true).putExtra("video",true));}
        catch(RuntimeException e){statusLine.setText("接收服务启动失败，请查看诊断页");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==PERMISSIONS){
            if(results.length==0){statusLine.setText("已取消权限申请");pending=false;return;}
            for(int r:results)if(r!=PackageManager.PERMISSION_GRANTED){statusLine.setText("需要蓝牙及附近设备权限才能开始连接");pending=false;return;}
            beginConnect();
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(!pending)return;
        if(request==ENABLE){if(result==RESULT_OK)beginConnect();else{statusLine.setText("蓝牙尚未开启");pending=false;}}
        if(request==DISCOVER){
            if(result<=0){statusLine.setText("已取消开始连接");pending=false;return;}
            startReceiver();
        }
    }
    /** Android 8.0 前无 startForegroundService：低版本直接 startService。 */
    private void startServiceCompat(Intent i){if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void applyOrientation(){
        setRequestedOrientation(landscape?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
    private void setFullscreen(boolean enabled){
        input.cancel();pointer=-1;fullscreen=enabled;
        View decor=getWindow().getDecorView();
        if(android.os.Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(!enabled);
        if(enabled){
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }else{
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        root.requestApplyInsets();
    }
    @Override public void onBackPressed(){if(fullscreen)setFullscreen(false);else super.onBackPressed();}
    @Override public void onConfigurationChanged(android.content.res.Configuration value){super.onConfigurationChanged(value);input.cancel();pointer=-1;applyCodeLayout();root.post(()->setFullscreen(fullscreen));}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("fullscreen",fullscreen);state.putBoolean("landscape",landscape);state.putBoolean("detailsExpanded",detailsExpanded);state.putBoolean("pending",pending);super.onSaveInstanceState(state);}
    private boolean touch(View view,MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            if(!sink.hasFrame()||!input.ready())return false;
            int[] pos=InputRouter.map(event.getX(),event.getY(),view.getWidth(),view.getHeight(),false);
            if(pos==null||!input.touch(0,pos[0],pos[1]))return false;pointer=event.getPointerId(0);return true;
        }
        if(pointer<0)return false;
        if(action==MotionEvent.ACTION_CANCEL){input.cancel();pointer=-1;return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN)return true;
        if(action==MotionEvent.ACTION_POINTER_UP&&event.getPointerId(event.getActionIndex())!=pointer)return true;
        int index=event.findPointerIndex(pointer);if(index<0){input.cancel();pointer=-1;return true;}
        if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){
            int[] pos=InputRouter.map(event.getX(index),event.getY(index),view.getWidth(),view.getHeight(),true);
            boolean up=action!=MotionEvent.ACTION_MOVE;
            if(pos!=null)input.touch(up?1:2,pos[0],pos[1]);else input.cancel();
            if(up){pointer=-1;view.performClick();}return true;
        }
        return true;
    }
    public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture value,int width,int height){
        try{sink.attach(surface,value);if(!foreground)sink.hide(surface);}
        catch(RuntimeException e){DiagnosticLog.add(this,"接收窗口附着失败："+e.getClass().getSimpleName());engine().stop(true);engine().reportProblem("画面窗口未能恢复，请重新连接。");}
    }
    public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture value,int width,int height){}
    public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture value){}
    public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture value){input.cancel();pointer=-1;return sink.detach(surface,value);}
    @Override protected void onResume(){super.onResume();foreground=true;if(surface!=null)sink.show(surface);if(root!=null)root.post(()->setFullscreen(fullscreen));}
    @Override protected void onPause(){foreground=false;input.cancel();pointer=-1;if(surface!=null)sink.hide(surface);super.onPause();}
    @Override protected void onStart(){super.onStart();uiHandler.removeCallbacks(updateStatus);uiHandler.post(updateStatus);}
    @Override protected void onStop(){uiHandler.removeCallbacks(updateStatus);super.onStop();}
    @Override protected void onDestroy(){uiHandler.removeCallbacksAndMessages(null);if(surface!=null)sink.hide(surface);super.onDestroy();}
}
