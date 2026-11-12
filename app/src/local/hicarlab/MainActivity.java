package local.hicarlab;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Experimental receiver: discovery, CCMP and native HiChain. No network/media. */
public class MainActivity extends ThemeActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<BluetoothServerSocket> servers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BluetoothSocket> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private TextView deviceText, logText;
    private final DiagnosticLog.Listener diagnosticListener = text -> { if (logText != null) logText.setText(text); };
    private ReceiverEngine engine() { return ((LabApplication)getApplication()).engine(); }
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private boolean running = false;
    private String snapshot = "";
    private static final int EXPORT = 20, DISCOVER = 21;
    private static final UUID FDEE = UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb");
    private EditText modelInput;
    private String localId, localPin;
    private final java.util.concurrent.atomic.AtomicBoolean authBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable backgroundStop = () -> stopBaseline(true);
    private boolean nativeReady;
    private boolean needBt = false;
    private void initIdentity() {
        WireIdentity.ensure(this);
        SharedPreferences pref = getSharedPreferences("identity", MODE_PRIVATE);
        localId = pref.getString("id", null); localPin = pref.getString("pin", null);
        java.security.SecureRandom random = new java.security.SecureRandom();
        if (localId == null || localPin == null) {
            byte[] seed = new byte[32]; random.nextBytes(seed); StringBuilder id = new StringBuilder();
            for (byte b : seed) id.append(String.format(Locale.ROOT, "%02X", b & 255));
            localId = id.toString(); localPin = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
            if (!pref.edit().putString("id", localId).putString("pin", localPin).commit()) throw new IllegalStateException("identity save");
        }
        try {
            File keys = getDir("huks", MODE_PRIVATE); android.system.Os.chmod(keys.getAbsolutePath(), 0700);
            nativeReady = NativeAuth.init(keys.getAbsolutePath(), localId) == 0;
            if (nativeReady) {
                try { NativeAuth.enablePskSpeke(getSharedPreferences("ui", 0).getBoolean("psk_speke", false) ? 1 : 0); }
                catch (Throwable ignored) { /* library built without the optional layer */ }
                try { NativeAuth.enableWireAuth(getSharedPreferences("ui", 0).getBoolean("wire_auth", false) ? 1 : 0); }
                catch (Throwable ignored) { /* library built without the optional layer */ }
            }
        } catch (Exception | LinkageError e) { nativeReady = false; }
    }
    /** 实验通道：允许 adb 用 Intent extra 精确设置开关，避免依赖不稳定的 UI 坐标。
     *  例：am start -n local.hicarlab/.MainActivity --ez psk_speke true --ez wire_auth true */
    private void applyIntentSwitches() {
        Intent intent = getIntent();
        if (intent == null) return;
        String[] keys = { "psk_speke", "wire_auth", "auto_probe_reconnect", "ccmp_initiator", "beacon13", "chan_sweep", "passive_business", "quiet_mode", "peer_scan", "scan_trigger", "gatt_probe", "gatt_ccmp", "gatt_direct", "profile_reconnect" };
        boolean changed = false;
        for (String key : keys) {
            if (!intent.hasExtra(key)) continue;
            boolean on = intent.getBooleanExtra(key, false);
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean(key, on).commit();
            log("adb 开关 " + key + "=" + on + "（已写入设置）");
            changed = true;
        }
        if (changed && nativeReady) {
            SharedPreferences ui = getSharedPreferences("ui", MODE_PRIVATE);
            try { NativeAuth.enablePskSpeke(ui.getBoolean("psk_speke", false) ? 1 : 0); } catch (Throwable ignored) {}
            try { NativeAuth.enableWireAuth(ui.getBoolean("wire_auth", false) ? 1 : 0); } catch (Throwable ignored) {}
        }
        /* 真车机的 authId 是 SHA256(8 位型号 + 本机 BR/EDR MAC)，HarmonyOS 手机
         * 会据此反查车机身份与长期公钥。adb 直写省去设置页弹窗：
         *   am start -n local.hicarlab/.MainActivity --es wire_mac AA:BB:CC:DD:EE:FF
         * 只改线上身份，不动 HUKS 硬件种子与六位连接码。 */
        String wireMac = intent.getStringExtra("wire_mac");
        if (wireMac != null) {
            String wireModel = intent.getStringExtra("wire_model");
            if (wireModel == null || wireModel.isEmpty()) wireModel = "00048000";
            try {
                WireIdentity.setExplicit(this, wireModel, wireMac);
                log("adb 已保存本机蓝牙地址派生身份（model=" + wireModel.trim().toUpperCase(Locale.ROOT)
                        + "）；HUKS 种子与连接码保持不变。显式指定的地址不会被自动纠正，"
                        + "请务必填系统真实 BR/EDR MAC；请在手机删除旧 HiCar 记录后重新输码配对。");
                WireIdentity.ensure(this); // 写入后复核：否则身份自检日志停留在写入前的旧值，容易误判
            } catch (IllegalArgumentException e) {
                log("adb 保存派生身份失败：" + e.getMessage() + "（地址须为真实本机蓝牙 MAC，不能填屏蔽地址 02:00:00:00:00:00）");
            }
        }
    }
    private boolean pendingFdee, pendingNetwork;
    private String pendingModel;
    private int sequence = 1;
    private static final UUID BASELINE = UUID.fromString("f97dd6c0-636a-4806-b65e-9bb413d81641");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        initIdentity();
        applyIntentSwitches();
        // 由主页（VideoActivity）在无蓝牙地址时跳转而来：标记 needBt，待下方 note 创建后再提示。
        Intent launch = getIntent();
        if (launch != null && launch.getBooleanExtra("need_bt_address", false)) {
            needBt = true;
        }
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        TextView title = new TextView(this); title.setText("设置与诊断 · 0.11.1"); title.setTextSize(25); root.addView(title);
        TextView note = new TextView(this);
        note.setText("设置与诊断\n日常连接请使用首页。下方提供接收设置、权限检查和连接诊断。\n下方保留分阶段测试，进行实验前请先停止当前连接。请停车后操作。");
        note.setTextSize(16); root.addView(note);
        if (needBt) note.setText("⚠ 尚未设置本机蓝牙地址：请先点下方「设置本机蓝牙身份」填写（从系统“关于手机/状态信息”查看蓝牙地址），保存后本页将自动进入主页。不填则无法进入首页接收。");
        deviceText = new TextView(this); deviceText.setTextIsSelectable(true); deviceText.setTextSize(15); root.addView(deviceText);
        button(root, "1. 授权并检查设备", () -> {
            if (Build.VERSION.SDK_INT >= 31 && !permitted()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 1);
            else refresh();
        });
        button(root, "2. 基础蓝牙测试（30 秒）", () -> start(false));
        TextView modelLabel = new TextView(this);
        modelLabel.setText("发现型号（高级设置）：00048000。仅在兼容性调试时修改。");
        root.addView(modelLabel);
        modelInput = new EditText(this); modelInput.setSingleLine(true); modelInput.setText("00048000"); root.addView(modelInput);
        button(root, "设置本机蓝牙身份（重连实验）", this::configureWireIdentity);
        button(root, "进入主页（开始接收）", () -> {
            if (WireIdentity.resolveMac(MainActivity.this) == null) {
                log("尚未设置本机蓝牙地址，无法进入主页。请先点“设置本机蓝牙身份”填写蓝牙地址（从系统状态信息查看）。");
                return;
            }
            startActivity(new Intent(MainActivity.this, VideoActivity.class));
        });
        button(root, "忘记已配对手机（重新配对）", () -> {
            if (running || engine().isRunning()) { log("请先停止实验，再忘记已配对手机。"); return; }
            engine().forgetPeer();
        });
        CheckBox pskBox = new CheckBox(this);
        pskBox.setText("PSK-SPEKE 免密重连（实验性，默认关闭；需两端运行同一实现）");
        pskBox.setChecked(getSharedPreferences("ui", 0).getBoolean("psk_speke", false));
        pskBox.setOnCheckedChangeListener((v, isChecked) -> {
            getSharedPreferences("ui", 0).edit().putBoolean("psk_speke", isChecked).apply();
            try { NativeAuth.enablePskSpeke(isChecked ? 1 : 0); } catch (Throwable ignored) {}
            log(isChecked ? "PSK-SPEKE 已启用：首次成功配对后，重连将由本机凭据自动完成。" : "PSK-SPEKE 已关闭，恢复每次输入连接码。");
        });
        root.addView(pskBox);
        CheckBox wireBox = new CheckBox(this);
        wireBox.setText("华为 AUTH_* 线协议兼容（真实手机互通实验，建议与 PSK-SPEKE 同开）");
        wireBox.setChecked(getSharedPreferences("ui", 0).getBoolean("wire_auth", false));
        wireBox.setOnCheckedChangeListener((v, isChecked) -> {
            getSharedPreferences("ui", 0).edit().putBoolean("wire_auth", isChecked).apply();
            try { NativeAuth.enableWireAuth(isChecked ? 1 : 0); } catch (Throwable ignored) {}
            log(isChecked ? "AUTH_* 兼容已启用：线上消息码 0x11/0x12，版本串 2.0.31。" : "AUTH_* 兼容已关闭，恢复标准 PAKE 码与 1.0.0。");
        });
        root.addView(wireBox);
        CheckBox probeBox = new CheckBox(this);
        probeBox.setText("车机主动回连已配对手机（默认开启；断线后车机主动连回手机，手机免弹窗恢复）");
        probeBox.setChecked(getSharedPreferences("ui", 0).getBoolean("auto_probe_reconnect", true));
        probeBox.setOnCheckedChangeListener((v, isChecked) -> {
            getSharedPreferences("ui", 0).edit().putBoolean("auto_probe_reconnect", isChecked).apply();
            log(isChecked ? "车机主动回连已启用（默认）：断线后由车机主动连回已配对手机，手机侧免弹窗、走 STS 0x11 免密恢复。" : "车机主动回连已关闭，仅保留持续广播等待手机连接。");
        });
        root.addView(probeBox);
        CheckBox initiatorBox = new CheckBox(this);
        initiatorBox.setText("无感回连（默认开启；车机主动拨手机 FE35 发起协商并连 FE36，静默窗口 45 秒，免手机点击「靠近发现」）");
        initiatorBox.setChecked(getSharedPreferences("ui", 0).getBoolean("ccmp_initiator", true));
        initiatorBox.setOnCheckedChangeListener((v, isChecked) -> {
            getSharedPreferences("ui", 0).edit().putBoolean("ccmp_initiator", isChecked).apply();
            log(isChecked ? "无感回连已启用（默认）：静默窗口 45 秒，车机主动发 CCMP start 帧并协商 business 通道；认证成功后关闭广播。" : "无感回连已关闭，恢复原有敲门路径（只建立 ACL 等手机主动连回）。");
        });
        root.addView(initiatorBox);
        CheckBox beaconBox = new CheckBox(this);
        beaconBox.setText("FDEE 广播使用 13 字节兼容变体（默认开启；用于真机对比 11/13 字节，关闭后发送 11 字节标准帧）");
        beaconBox.setChecked(getSharedPreferences("ui", 0).getBoolean("beacon13", true));
        beaconBox.setOnCheckedChangeListener((v, isChecked) -> {
            getSharedPreferences("ui", 0).edit().putBoolean("beacon13", isChecked).apply();
            log(isChecked ? "FDEE 广播将使用 13 字节 `... 10 seq 11 e7` 变体（下次开始接收生效）。" : "FDEE 广播将使用 11 字节标准帧（下次开始接收生效）。");
        });
        root.addView(beaconBox);
        CheckBox profileBox = new CheckBox(this);
        profileBox.setText("系统 profile 主动回连实验（默认关闭；反射 A2DP/HEADSET connect + 拨标准 profile RFCOMM 并保持 ACL，静默窗口 45 秒）");
        profileBox.setChecked(getSharedPreferences("ui", 0).getBoolean("profile_reconnect", false));
        profileBox.setOnCheckedChangeListener((v, isChecked) -> {
            getSharedPreferences("ui", 0).edit().putBoolean("profile_reconnect", isChecked).apply();
            log(isChecked ? "系统 profile 回连实验已启用：静默窗口 45 秒，尝试触发手机 reconnectType 2。" : "系统 profile 回连实验已关闭，恢复原有敲门路径。");
        });
        root.addView(profileBox);
        TextView pinText = new TextView(this); pinText.setText("本机实验连接码：" + localPin); pinText.setTextSize(23); root.addView(pinText);
        TextView nativeText = new TextView(this); nativeText.setText(nativeReady ? "HiChain 原生库：已加载（不代表手机认证成功）" : "HiChain 原生库：初始化失败，不能启动认证实验"); root.addView(nativeText);
        button(root, "3. 发现与认证实验（120 秒）", () -> {
            if (!ready()) return;
            pendingNetwork = false;
            if (running || engine().isRunning()) { log("实验仍在运行，不重复启动。需要重试时请先点停止测试。"); return; }
            if (!nativeReady) { log("认证后端不可用，未启动实验。"); return; }
            pendingModel = modelInput.getText().toString().trim().toUpperCase(Locale.ROOT);
            try { DiscoveryBeacon.encode(pendingModel, sequence); }
            catch (IllegalArgumentException e) { log("实验型号必须恰好为 8 位十六进制字符。"); return; }
            stop(false); pendingFdee = true; pendingNetwork=false; pendingVideo=false;
            try {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
                startActivityForResult(intent, DISCOVER);
            } catch (RuntimeException e) { pendingFdee = false; log("系统无法显示可发现请求：" + e.getClass().getSimpleName()); }
        });
        button(root, "4. 仅 FE35 / FE36 监听（120 秒）", () -> {
            if (!ready()) return;
            stop(false); pendingFdee = false;
            try {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
                startActivityForResult(intent, DISCOVER);
            } catch (RuntimeException e) { log("系统无法显示可发现请求：" + e.getClass().getSimpleName()); }
        });
        button(root,"5. 临时热点与网络实验（120 秒）",()->prepareNetwork(false));
        button(root,"6. 持续接收（画面、触控、声音）",()->prepareNetwork(true));
        button(root, "停止测试", () -> { log("用户点击停止测试。"); stop(true); });
        button(root, "导出诊断报告", () -> {
            log("导出报告请求停止当前测试。"); stop(false);
            refresh();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("text/plain"); intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE, "HiCarLab-report.txt");
            try { startActivityForResult(intent, EXPORT); }
            catch (RuntimeException e) { log("文件选择器不可用，可长按下方文字复制或拍照。"); }
        });
        TextView tips = new TextView(this);
        tips.setText("协议移植参考 Aki894/CarLink（MIT），认证后端采用 OpenHarmony DeviceAuth/HUKS。连接码保存在应用私有目录，不写入报告；删除应用数据会更换身份。0.8.1免输码重连已由用户确认；可在本机填写蓝牙地址保存派生身份。第5项创建临时热点并在结束时释放；Android 16 以上实验通过本地热点 SystemApi 状态回调取得地址；普通应用能否调用及兼容性待实测。不调用 GPS 或扫描附近网络。第6项为画面与触控实验；触控已实测；声音已实测；0.8.3连续播放及断线重连已实测；本版锁屏/后台恢复待验证。\n报告仅记录设备参数和协议状态，不保存对端身份、原始认证包、PIN 或密钥。"); root.addView(tips);
        logText = new TextView(this); logText.setTextIsSelectable(true); logText.setTextSize(14); root.addView(logText);
        setContentView(scroll);
        refresh();
        log("实验状态：VIDEO_INPUT_AUDIO_EXPERIMENTAL");
        LocalIdentityProbe.run(getApplicationContext());
    }
    private boolean pendingVideo;
    private void configureWireIdentity(){
        if(running || engine().isRunning()) { log("请先停止实验，再设置本机蓝牙身份。"); return; }
        final String model=modelInput.getText().toString().trim().toUpperCase(Locale.ROOT);
        try { DiscoveryBeacon.encode(model,0); } catch(IllegalArgumentException e){ log("请先填写正确的实验型号。"); return; }
        EditText input=new EditText(this); input.setSingleLine(true);
        input.setHint("AA:BB:CC:DD:EE:FF");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this)
            .setTitle("填写接收端本机蓝牙地址")
            .setMessage("从本机系统状态信息查看蓝牙地址。请勿填写华为手机地址或 Wi-Fi 地址。地址会单独保存，身份丢失时可自动重算。保存后需在华为删除一次本次实验 HiCar 记录，再输码配对；随后再测试免输码重连。原连接码与密钥目录保持。")
            .setView(input).setNegativeButton("取消",(d,w)->input.setText(""))
            .setNeutralButton("打开本机信息",(d,w)->{
                input.setText("");
                try{startActivity(new Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS));}
                catch(RuntimeException e){log("请手动打开系统设置中的关于手机/状态信息，查看蓝牙地址。");}
            })
            .setPositiveButton("保存派生身份",null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(running || engine().isRunning()) { input.setError("请先停止实验"); return; }
            try{
                String address=input.getText().toString().trim().toUpperCase(Locale.ROOT);
                WireIdentity.setExplicit(MainActivity.this, model, address);
                input.setText(""); dialog.dismiss();
                log("已保存本机蓝牙地址派生身份；未更换 HUKS 种子或连接码。如装到其他设备，启动时会自动重置为本机地址。"
                        + "请先在该手机删除旧 HiCar 记录并重新输码配对一次，再验证免输码重连。");
                if (needBt) { needBt = false; startActivity(new Intent(MainActivity.this, VideoActivity.class)); }
            }catch(IllegalArgumentException e){input.setError("请填写系统状态信息里显示的本机蓝牙地址，不能使用屏蔽地址 02:00:00:00:00:00");}
        }));
        dialog.show();
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void prepareNetwork(boolean video){
            if (!ready()) return;
            if (running || engine().isRunning()) { if(video && engine().isRunning()){startActivity(new Intent(this,VideoActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));}else log("实验仍在运行；要更换模式请先点停止。"); return; }
            if (Build.VERSION.SDK_INT >= 23) {
                String permission = Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : Manifest.permission.ACCESS_FINE_LOCATION;
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{permission}, 7); log("请允许本轮 Wi-Fi 权限后再点第5项。"); return; }
            }
            pendingModel = modelInput.getText().toString().trim().toUpperCase(Locale.ROOT);
            try { DiscoveryBeacon.encode(pendingModel, 0); } catch (IllegalArgumentException e) { log("实验型号格式不正确。"); return; }
            stop(false); pendingFdee = true; pendingNetwork = true; pendingVideo=video;
            Intent discover = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            startActivityForResult(discover, DISCOVER);
    }
    private void button(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this); button.setText(label);
        button.setOnClickListener(v -> action.run()); root.addView(button);
    }
    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }
    private boolean ready() {
        if (!permitted()) { log("请先点击“授权并检查设备”，允许附近设备权限。"); return false; }
        if (adapter == null) { log("Android 未向应用提供蓝牙适配器；车机蓝牙可能由独立模块控制。"); return false; }
        try { if (!adapter.isEnabled()) { log("请先在车机设置中打开蓝牙，再返回测试。"); return false; } }
        catch (RuntimeException e) { log("无法访问蓝牙状态：" + e.getClass().getSimpleName()); return false; }
        return true;
    }
    private void refresh() {
        StringBuilder s = new StringBuilder();
        s.append("\n厂商：").append(Build.MANUFACTURER).append("\n型号：").append(Build.MODEL);
        s.append("\nAndroid：").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT);
        s.append("\n系统报告 ABI：").append(Arrays.toString(Build.SUPPORTED_ABIS));
        s.append("\n32 位 ABI：").append(Arrays.toString(Build.SUPPORTED_32_BIT_ABIS));
        s.append("\n64 位 ABI：").append(Arrays.toString(Build.SUPPORTED_64_BIT_ABIS));
        // Process.is64Bit() 是 API 23；Android 5.x 没有该接口，且不存在 64 位应用进程。
        s.append("\n当前进程：").append(Build.VERSION.SDK_INT>=23 ? (android.os.Process.is64Bit() ? "64 位" : "32 位") : "32 位（本系统无进程位数接口）");
        s.append("\nAndroid 蓝牙适配器：").append(adapter != null ? "存在" : "不可见");
        s.append("\nBLE 系统特性：").append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        s.append("\n附近设备权限：").append(permitted() ? "已授权／无需运行时申请" : "未授权");
        if (adapter != null && permitted()) try {
            s.append("\n蓝牙已开启：").append(adapter.isEnabled());
            s.append("\n多路广播能力（系统报告）：").append(adapter.isMultipleAdvertisementSupported());
            s.append("\nBLE 广播器：").append(adapter.getBluetoothLeAdvertiser() != null ? "可获取" : "不可获取");
        } catch (RuntimeException e) { s.append("\n蓝牙查询异常：").append(e.getClass().getSimpleName()); }
        s.append("\nH.264 解码器：");
        try {
            boolean found = false;
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) if (type.equalsIgnoreCase("video/avc")) {
                    s.append("\n  ").append(codec.getName()); found = true;
                }
            }
            if (!found) s.append("未枚举到");
        } catch (RuntimeException e) { s.append("枚举失败：").append(e.getClass().getSimpleName()); }
        s.append("\n以上为系统报告，未验证视频解码与实际无线互通。\n");
        snapshot = s.toString(); deviceText.setText(snapshot);
    }
    private void log(String message) { DiagnosticLog.add(getApplicationContext(), message); }
    private void start(boolean experimental) {
        if (!ready()) return;
        stop(false);
        running = true;
        int token = generation.incrementAndGet();
        if (experimental) {
            log("开始 FE35/FE36 实验监听：120 秒；无 HiCar 广播、无认证响应。");
            listen("HiCarLab FE35 probe", UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb"), token, "FE35");
            listen("HiCarLab FE36 probe", UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"), token, "FE36");
        } else {
            log("开始基础测试：30 秒；私有测试 UUID，不模拟 HiCar。");
            listen("HiCarLab baseline", BASELINE, token, "基础 RFCOMM");
            try {
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser == null) log("BLE 广播无法启动：广播器不可用。");
                else {
                    final BluetoothLeAdvertiser thisAdvertiser = advertiser;
                    advCallback = new AdvertiseCallback() {
                        @Override public void onStartSuccess(AdvertiseSettings settings) {
                            if (generation.get() == token) log("BLE 广播启动成功（私有 UUID，未验证手机接收）。");
                            else try { thisAdvertiser.stopAdvertising(this); } catch (RuntimeException ignored) { }
                        }
                        @Override public void onStartFailure(int error) {
                            if (generation.get() == token) log("BLE 广播失败：" + advertiseError(error));
                        }
                    };
                    advertiser.startAdvertising(new AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setConnectable(false).setTimeout(30000).build(),
                        new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(BASELINE)).build(), advCallback);
                }
            } catch (RuntimeException e) { log("BLE 广播异常：" + e.getClass().getSimpleName()); }
        }
        handler.postDelayed(() -> {
            if (generation.get() == token) { log("测试时间结束。"); stop(true); }
        }, experimental ? 120000 : 30000);
    }
    private void startFdee() {
        if (!ready()) return;
        Intent service = new Intent(this, ReceiverService.class).putExtra("model", pendingModel).putExtra("network", pendingNetwork).putExtra("video",pendingVideo);
            try { startServiceCompat(service); if(pendingVideo)startActivity(new Intent(this,VideoActivity.class)); }
        catch (RuntimeException e) { log("无法启动前台测试服务：" + e.getClass().getSimpleName()); }
    }
    /** Android 8.0 前无 startForegroundService：低版本直接 startService（旧系统无后台启动限制）。 */
    private void startServiceCompat(Intent i){ if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); }
    private void listen(String name, UUID uuid, int token, String label) {
        new Thread(() -> {
            BluetoothServerSocket server = null;
            try {
                server = adapter.listenUsingRfcommWithServiceRecord(name, uuid);
                servers.add(server);
                if (generation.get() != token) return;
                log(label + "：RFCOMM/SDP 注册成功；不代表收到手机连接。");
                while (generation.get() == token) {
                    BluetoothSocket socket = server.accept();
                    clients.add(socket);
                    if (generation.get() != token) { socket.close(); clients.remove(socket); break; }
                    log(label + "：收到蓝牙连接（未验证对端身份，未完成 HiCar 认证）。");
                    // No handshake guesses, no outbound packets; only count incoming bytes.
                    final BluetoothSocket accepted = socket;
                    new Thread(() -> receive(accepted, token, label), "lab-rx").start();
                }
            } catch (IOException e) {
                if (generation.get() == token) log(label + "：监听异常关闭（I/O 错误）。");
            } catch (RuntimeException e) {
                if (generation.get() == token) log(label + "：接口失败 " + e.getClass().getSimpleName());
            } finally {
                if (server != null) { servers.remove(server); try { server.close(); } catch (IOException ignored) { } }
            }
        }, "lab-listen").start();
    }
    private void receive(BluetoothSocket socket, int token, String label) {
        int total = 0;
        handler.postDelayed(() -> { try { socket.close(); } catch (IOException ignored) { } }, 10000);
        try {
            byte[] buffer = new byte[1024];
            InputStream input = socket.getInputStream();
            while (generation.get() == token && total < 4096) {
                int n = input.read(buffer, 0, Math.min(buffer.length, 4096 - total));
                if (n < 0) break;
                total += n;
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(socket); try { socket.close(); } catch (IOException ignored) { }
            if (generation.get() == token) log(label + "：接收 " + total + " 字节；内容未保存，未发送响应。");
        }
    }
    private static String advertiseError(int value) {
        switch (value) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE: return "广播数据过长 (1)";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: return "广播资源不足 (2)";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED: return "已经启动 (3)";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR: return "系统内部错误 (4)";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "功能不支持 (5)";
            default: return "错误码 " + value;
        }
    }
    private void stop(boolean announce) { engine().stop(announce); stopBaseline(announce); }
    private void stopBaseline(boolean announce) {
        boolean wasRunning = running;
        running = false; generation.incrementAndGet();
        if (advertiser != null && advCallback != null) try { advertiser.stopAdvertising(advCallback); } catch (RuntimeException ignored) { }
        advertiser = null; advCallback = null;
        for (BluetoothServerSocket server : servers) try { server.close(); } catch (IOException ignored) { }
        servers.clear();
        for (BluetoothSocket socket : clients) try { socket.close(); } catch (IOException ignored) { }
        clients.clear();
        if (announce && wasRunning) log("测试已停止，实验监听与 BLE 广播已关闭。系统蓝牙可发现状态按系统倒计时结束。");
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results); refresh();
        if(code==8) log(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ? "扫描权限已授予，请打开系统位置开关，再点第5项；本轮尚未开始。" : "扫描需要精确位置权限，本轮尚未开始。");
        else if(code==7) log("Wi-Fi 权限请求已返回，请再点第5项检查。");
        else log(permitted() ? "蓝牙权限已就绪，请继续测试。" : "蓝牙权限未全部授予。");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == DISCOVER) {
            if (result > 0) { if (pendingFdee) startFdee(); else start(true); }
            else log("未获准进入蓝牙可发现状态，未启动实验监听。");
        }
        if (request == EXPORT && result == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IOException("No output stream");
                String report = "HiCarLab 0.11.1\nAuthentication/network: EXPERIMENTAL; video/input/audio: EXPERIMENTAL\n" + snapshot + "\n事件日志\n" + DiagnosticLog.history(getApplicationContext());
                out.write(report.getBytes(StandardCharsets.UTF_8)); log("报告已导出。");
            } catch (IOException | RuntimeException e) { log("导出失败：" + e.getClass().getSimpleName()); }
        }
    }
    @Override protected void onStart() { super.onStart(); handler.removeCallbacks(backgroundStop); DiagnosticLog.attach(getApplicationContext(), diagnosticListener); }
    @Override protected void onStop() { DiagnosticLog.detach(diagnosticListener); if (!engine().isRunning()) handler.postDelayed(backgroundStop, 30000); super.onStop(); }
    @Override protected void onDestroy() { DiagnosticLog.detach(diagnosticListener); stopBaseline(false); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
