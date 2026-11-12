package local.hicarlab;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class ReceiverEngine {
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<BluetoothServerSocket> servers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BluetoothSocket> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean authBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final CopyOnWriteArrayList<TestSession> sessions=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MicrophoneSender> microphones=new CopyOnWriteArrayList<>();
    private boolean microphoneAllowed;
    String microphoneStatus(){if(!MicrophoneSender.enabled(context))return "麦克风已关闭";for(MicrophoneSender m:microphones)return m.status();return isActive()?"麦克风尚未就绪，重新连接后启用":"麦克风已启用，等待连接";}
    void disableMicrophone(){microphoneAllowed=false;for(MicrophoneSender m:microphones)m.stop();}
    private final CopyOnWriteArrayList<AudioReceiver> audioReceivers=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<HiSightClient> videoClients=new CopyOnWriteArrayList<>();
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advCallback;
    private volatile boolean running;
    private final ReceiverStatus status=new ReceiverStatus();
    private final ReconnectGate reconnect = new ReconnectGate();
    private int rebindAttempts = 0;
    private volatile boolean continuous;
    private P2pHotspot hotspot;
    private LocalHotspot localHotspot;
    private volatile NetworkResource networkResource;
    private final Object networkLock = new Object();
    private final CopyOnWriteArrayList<java.net.ServerSocket> tcpServers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<java.net.Socket> tcpClients = new CopyOnWriteArrayList<>();
    private boolean nativeReady;
    private String localId,localPin,pendingModel;
    private String peerMac;
    private int sequence;
    // 当前会话上下文：断线后车机主动回连手机时，复用同一 handshake 链路（protocolReceive）
    private TestSession activeSession;
    private int activeToken;
    private String activeModel;
    private int activeChannel;
    private Runnable onStopped = () -> {};
    private static final UUID FDEE = UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb");
    private static final UUID FE35 = UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb");
    /** 车机主动回连（无感回连）：默认开启。车机主动拨手机 FE35 并作为 CCMP initiator
     *  完成 Nearby 协商，再拨手机 FE36，手机不会进入「靠近发现」等待点击状态。 */
    private static final int INITIATOR_WINDOW_MS = 45000;
    private boolean ccmpInitiatorEnabled(){return context.getSharedPreferences("ui",0).getBoolean("ccmp_initiator",true);}
    /** 真机对比用：true 时发送 13 字节 `... 10 seq 11 e7` 变体（此前实际触发过手机回连的格式）。 */
    private boolean beaconLegacy13(){return context.getSharedPreferences("ui",0).getBoolean("beacon13",true);}
    /** 真机兜底：FE35 服务名解析打不开时，按上游 sdptool 语义枚举 RFCOMM 通道。默认开启。 */
    private boolean chanSweepEnabled(){return context.getSharedPreferences("ui",0).getBoolean("chan_sweep",true);}
    /** 无感回连时是否「被动等手机连回 FE36」。
     *  实测（2026-11-10）：false 时车机按协议向手机索取业务端口并主动拨过去，
     *  手机按已配对设备直接免密认证，用户无需点卡片；true 会一直干等。
     *  默认 false（即主动索取端口）；保留开关以便回退。 */
    private boolean passiveBusiness(){return context.getSharedPreferences("ui",0).getBoolean("passive_business",false);}
    /** 本轮 FDEE 广播数据，断线后恢复广播兜底时复用。 */
    private volatile byte[] discoveryPayload;
    /** 同一代次只允许一个主动回连线程，避免广播/服务回调重复拉起。
     *  用 token+epoch 共同标识一轮回连，旧线程退出时不会误清新线程的占用标记。 */
    private int reconnectLoopToken = -1;
    private int reconnectLoopEpoch = -1;
    /** 同一代次内可能发生多次断开/回连；epoch 让旧回连线程在下一轮检查时退出，
     *  避免旧线程与新线程同时拨号。由 requestReconnect 在每次断开时递增。 */
    private volatile int reconnectEpoch;
    /** 当前由车机主动拨通的控制通道；断开重连时主动关闭它，让旧回连线程立即退出。 */
    private volatile BluetoothSocket initiatorControl;
    private void nextReconnectEpoch(){
        BluetoothSocket stale;
        synchronized(this){
            reconnectEpoch++; reconnectLoopToken=-1; reconnectLoopEpoch=-1;
            stale=initiatorControl; initiatorControl=null;
        }
        if(stale!=null)closeQuietly(stale);
    }
    private boolean reconnectStillWanted(int token,int epoch){
        return generation.get()==token && continuous && running && reconnectEpoch==epoch;
    }
    /** 系统 profile 主动回连实验：默认关闭，只由 adb --ez profile_reconnect true 或设置页开关启用。 */
    private static final int PROFILE_WINDOW_MS = 45000;
    private volatile int profileTriedToken = -1;
    private boolean profileReconnectEnabled(){return context.getSharedPreferences("ui",0).getBoolean("profile_reconnect",false);}
    ReceiverEngine(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        initIdentity();
        configureDisplay();
        peerMac = context.getSharedPreferences("identity", 0).getString("peer_mac", null);
        if (peerMac != null) log("已载入已配对手机蓝牙地址 " + peerMac + "，将仅接受该设备自动重连。");
    }
    /** Report the device's real landscape resolution to the phone so the projection fills the screen. */
    private void configureDisplay(){
        try{
            android.view.WindowManager wm=(android.view.WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point p=new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            VideoSpec.configure(p.x, p.y);
            log("显示规格按设备真实分辨率配置为 "+VideoSpec.WIDTH+"×"+VideoSpec.HEIGHT+"（投屏铺满，不变形、不裁切）。");
        }catch(RuntimeException e){VideoSpec.configure(1920,1080);log("无法读取屏幕分辨率，回退到 1920×1080。");}
    }
    boolean isRunning() { return running; }
    boolean isActive(){return running || continuous;}
    ReceiverStatus.Snapshot displayStatus(){
        boolean decoded=false;
        if(running)for(TestSession session:sessions)if(session.mediaBusy.get()&&session.controlBusy.get()&&((LabApplication)context).video().hasDecoded()){decoded=true;break;}
        return status.snapshot(decoded);
    }
    String connectionStatus(){return displayStatus().title;}
    void reportProblem(String hint){if(!isActive())status.failed(generation.get(),hint);}
    private void fail(String hint){stop(true);status.failed(generation.get(),hint);}
    private void fail(int token,String hint){handler.post(()->{if(generation.get()==token)fail(hint);});}
    void setOnStopped(Runnable onStopped) { this.onStopped = onStopped; }
    private void log(String text) { DiagnosticLog.add(context, text); }
    private final class TestSession {
        final String model; final boolean video;
        final java.util.concurrent.atomic.AtomicBoolean mediaBusy=new java.util.concurrent.atomic.AtomicBoolean();
        TestSession(String model,boolean video){this.model=model;this.video=video;}
        volatile boolean authenticated;
        NetworkResource network;
        private String peer;
        private byte[] key;
        final java.util.concurrent.atomic.AtomicBoolean controlBusy=new java.util.concurrent.atomic.AtomicBoolean();
        synchronized void setKey(byte[] k){if(key!=null)Arrays.fill(key,(byte)0);key=k.clone();}
        synchronized byte[] copyKey(){return key==null?null:key.clone();}
        synchronized void clearKey(){if(key!=null)Arrays.fill(key,(byte)0);key=null;}
        synchronized boolean select(BluetoothSocket socket) {
            String address = socket.getRemoteDevice().getAddress();
            if (peer != null) return peer.equals(address);
            if (peerMac != null) return peerMac.equalsIgnoreCase(address); // 已配对：仅接受该手机回连
            peer = address; return true;                                   // 首次配对：接受任意设备
        }
    }
    private void initIdentity() {
        WireIdentity.ensure(context);
        SharedPreferences pref = context.getSharedPreferences("identity", 0);
        localId = pref.getString("id", null); localPin = pref.getString("pin", null);
        java.security.SecureRandom random = new java.security.SecureRandom();
        if (localId == null || localPin == null) {
            byte[] seed = new byte[32]; random.nextBytes(seed); StringBuilder id = new StringBuilder();
            for (byte b : seed) id.append(String.format(Locale.ROOT, "%02X", b & 255));
            localId = id.toString(); localPin = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
            if (!pref.edit().putString("id", localId).putString("pin", localPin).commit()) throw new IllegalStateException("identity save");
        }
        try {
            File keys = context.getDir("huks", 0); android.system.Os.chmod(keys.getAbsolutePath(), 0700);
            nativeReady = NativeAuth.init(keys.getAbsolutePath(), localId) == 0;
            if (nativeReady) {
                SharedPreferences ui = context.getSharedPreferences("ui", 0);
                try { NativeAuth.enablePskSpeke(ui.getBoolean("psk_speke", false) ? 1 : 0); }
                catch (Throwable ignored) { /* library built without the optional layer */ }
                try { NativeAuth.enableWireAuth(ui.getBoolean("wire_auth", false) ? 1 : 0); }
                catch (Throwable ignored) { /* library built without the optional layer */ }
            }
        } catch (Exception | LinkageError e) { nativeReady = false; }
    }
    void start(String model, boolean withNetwork) {start(model,withNetwork,false);}
    void start(String model,boolean withNetwork,boolean withVideo){start(model,withNetwork,withVideo,false);}
    void start(String model,boolean withNetwork,boolean withVideo,boolean withMicrophone){ rebindAttempts=0; beginSession(model,withNetwork,withVideo,withMicrophone,false); }
    void start(String model,boolean withNetwork,boolean withVideo,boolean withMicrophone,boolean preserveVideo){ rebindAttempts=0; beginSession(model,withNetwork,withVideo,withMicrophone,preserveVideo); }
    private void beginSession(String model,boolean withNetwork,boolean withVideo,boolean withMicrophone){ beginSession(model,withNetwork,withVideo,withMicrophone,false); }
    private void beginSession(String model,boolean withNetwork,boolean withVideo,boolean withMicrophone,boolean preserveVideo){
        if (running) { log("认证测试仍在运行，不重复启动。"); return; }
        if(android.os.Build.VERSION.SDK_INT>=31 && (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)){fail("蓝牙权限不足，请在系统设置中允许附近设备权限。");return;}
        if(!nativeReady){fail("连接组件未就绪，请重新打开应用或查看诊断页。");return;}
        if(adapter==null){fail("这台设备没有可用的蓝牙适配器。");return;}
        try{if(!adapter.isEnabled()){fail("蓝牙已关闭，请打开蓝牙后重新连接。");return;}}catch(SecurityException e){fail("蓝牙权限不足，请在系统设置中允许附近设备权限。");return;}
        pendingModel = model;
        WireIdentity.ensure(context);
        SharedPreferences identity = context.getSharedPreferences("identity", 0);
        localId = WireIdentity.select(identity.getString("id", null), model,
                identity.getString("wire_model", ""), identity.getString("wire_id", ""));
        if (localId.equals(identity.getString("id", ""))) {
            String live = WireIdentity.DEFAULT_MODEL;
            log("本轮未使用本机蓝牙地址派生身份（wire_mac 未配置或自动探测失败），手机按 SHA256(model+本机MAC) 反查将不匹配，免输码重连可能不可用；"
                    + "如重连失败，请在设置页“设置本机蓝牙身份”填写本机蓝牙地址，或 adb 传入 --es wire_mac。");
        } else {
            log("本轮使用本机蓝牙地址派生身份（model=" + identity.getString("wire_model", WireIdentity.DEFAULT_MODEL)
                    + "）；原 HUKS 种子与连接码保持；已具备免输码重连条件。");
        }
        stopInternal(false,preserveVideo,false); running = true;
        continuous = withVideo;microphoneAllowed=withVideo&&withMicrophone;
        reconnect.reset();
        sequence = context.getSharedPreferences("identity", 0).getInt("discovery_sequence", 0) & 255;
        context.getSharedPreferences("identity", 0).edit().putInt("discovery_sequence", (sequence + 1) & 255).apply();
        int token = generation.incrementAndGet();
        status.begin(token);
        final byte[] payload = DiscoveryBeacon.encode(pendingModel, sequence, beaconLegacy13());
        discoveryPayload = payload;
        log("FDEE 实验开始：型号 " + pendingModel + "，序号 " + sequence
                + "，广播 " + payload.length + " 字节（" + (beaconLegacy13() ? "13 字节兼容变体" : "11 字节标准帧") + "）。");
        sequence = (sequence + 1) & 255;
        if (withNetwork) {
            P2pHotspot.Events networkEvents = new P2pHotspot.Events() {
                public void log(String message) { ReceiverEngine.this.log(message); }
                public void failed(String reason) { if (generation.get() == token) { ReceiverEngine.this.log(reason); fail(token,(reason.contains("BSSID")||reason.contains("可核验的唯一地址"))?"热点已创建，但系统隐藏了本轮热点地址；当前连接方式需要兼容性适配。":"无法准备无线连接："+reason); } }
                public void ready(NetworkResource.Offer offer) {
                    if (generation.get() != token) return;
                    networkResource = new NetworkResource(offer);
                    startProtocol(token, pendingModel, withVideo, () -> startDiscoveryOrSilentReconnect(token, payload));
                }
            };
            if (Build.VERSION.SDK_INT >= 33) { localHotspot = new LocalHotspot(context, networkEvents); localHotspot.start(); }
            else { hotspot = new P2pHotspot(context, networkEvents); hotspot.start(); }
        } else startProtocol(token, pendingModel, withVideo, () -> startDiscoveryOrSilentReconnect(token, payload));
        if (!continuous) handler.postDelayed(() -> { if (generation.get() == token) { log("FDEE 实验时间结束。"); stop(true); } }, 120000);
        else log("持续接收已启动：不再120秒自动停止；断线后重新等待连接，可随时手动停止。");
    }
    /** 静默重连窗口（毫秒）：模仿盒子 libnearby 的 reconnecting 态——已有配对手机时先不开 FDEE 广播，
     *  只做主动 RFCOMM 敲门；手机扫不到"新设备"就不会触发 FindNearbyState 弹窗。超时再开广播兜底。 */
    private static final int SILENT_RECONNECT_MS = 12000;
    private volatile boolean silentWindowConnected;
    /** 广播启动调度：无配对手机（首次发现场景）直接广播；已有配对手机（断线重连场景）先静默敲门。 */
    /** 诊断/功能实验：车机作为 BLE 中心主动扫描已配对手机。真实盒子是「主动扫描 + 自动连接手机」，
     *  这里先只观察手机在 HiCar 就绪时到底广播了什么，不做连接动作。默认关闭。 */
    private boolean peerScanEnabled(){return context.getSharedPreferences("ui",0).getBoolean("peer_scan",false);}
    /** 功能实验：扫描触发回连。完全不发 FDEE 广播，只扫描已配对手机的 FDEE 广播，
     *  一看到手机在广播就立刻拨它的 FE35（真实盒子是主动扫描手机并自动连接）。
     *  目的是避免手机把车机当成「靠近发现的新设备」而弹确认卡片。默认关闭。 */
    private boolean scanTriggerEnabled(){return context.getSharedPreferences("ui",0).getBoolean("scan_trigger",false);}
    /** 诊断：作为 BLE 中心连上手机，枚举 HiCar 相关 GATT 服务/特征（只读）。默认关闭。 */
    private boolean gattProbeEnabled(){return context.getSharedPreferences("ui",0).getBoolean("gatt_probe",false);}
    /** 功能实验：BLE 中心连上手机后，在 FE35 GATT 上执行 CCMP 协商（写 2a02 / 听 2a03）。默认关闭。 */
    private boolean gattCcmpEnabled(){return context.getSharedPreferences("ui",0).getBoolean("gatt_ccmp",false);}
    /** 实验：不等手机广播，直接按已配对地址连 BLE（实测手机未广播时返回 133，默认关闭）。 */
    private boolean gattDirectEnabled(){return context.getSharedPreferences("ui",0).getBoolean("gatt_direct",false);}
    /** 车机自己的 FE36 业务通道号（手机连回来时用）；还没算出时按默认 4。 */
    private int businessChannel(){return activeChannel>0?activeChannel:4;}
    private long lastScanDial;
    private long lastGattProbe;
    private PeerGattProbe gattProbe;
    private android.bluetooth.le.ScanCallback peerScanCallback;
    private android.bluetooth.le.BluetoothLeScanner peerScanner;
    private long peerScanLogged;
    private void startPeerScan(final int token) {
        if (!peerScanEnabled() && !scanTriggerEnabled() && !gattProbeEnabled()) return;
        if (adapter == null || peerMac == null) return;
        synchronized (this) { if (peerScanCallback != null) return; }
        try {
            android.bluetooth.le.BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { log("BLE 主动扫描不可用：系统未提供扫描器。"); return; }
            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            final java.util.Set<String> seen = new java.util.HashSet<>();
            android.bluetooth.le.ScanCallback callback = new android.bluetooth.le.ScanCallback() {
                @Override public void onScanResult(int type, android.bluetooth.le.ScanResult result) {
                    String address = result.getDevice() == null ? "?" : result.getDevice().getAddress();
                    boolean interesting = looksLikeHicar(result.getScanRecord());
                    if (!interesting && !seen.add(address)) return;
                    long now = System.currentTimeMillis();
                    if (now - peerScanLogged < 1000) return;
                    peerScanLogged = now;
                    if (seen.size() > 40 && !interesting) return;
                    log("BLE 扫描：" + address + " " + describeScan(result) + (interesting ? "  <= HiCar/华为特征" : ""));
                    if (scanTriggerEnabled() && interesting && peerMac != null
                            && address.equalsIgnoreCase(peerMac) && now - lastScanDial > 8000) {
                        lastScanDial = now;
                        log("扫描触发：检测到手机的 FDEE 广播，立即主动拨它的 FE35 发起无感回连（本端不广播）。");
                        startReconnectToPeer();
                    }
                    if (gattProbeEnabled() && interesting && peerMac != null
                            && address.equalsIgnoreCase(peerMac) && now - lastGattProbe > 30000) {
                        lastGattProbe = now;
                        if (gattProbe == null || !gattProbe.busy()) {
                            final int businessChannel = businessChannel();
                            gattProbe = new PeerGattProbe(context, localId, businessChannel,
                                    gattCcmpEnabled(), new PeerGattProbe.Sink() {
                                @Override public void log(String message) { ReceiverEngine.this.log(message); }
                                @Override public void endpoint(int channel) {
                                    ReceiverEngine.this.log("GATT CCMP：手机返回业务通道 " + channel + "；等待手机连回车机 FE36（通道 " + businessChannel + "）。");
                                }
                            });
                            gattProbe.connect(result.getDevice());
                        }
                    }
                }
                @Override public void onScanFailed(int errorCode) { log("BLE 主动扫描失败：errorCode=" + errorCode); }
            };
            synchronized (this) { peerScanner = scanner; peerScanCallback = callback; }
            scanner.startScan(null, settings, callback);
            log("BLE 主动扫描已启动（无过滤）：记录周边广播，重点标记 FDEE / 华为厂商数据（手机可能用随机地址，按 MAC 过滤会扫不到）。");
            if (gattProbeEnabled() && gattCcmpEnabled() && gattDirectEnabled()) startGattDirectLoop(token);
        } catch (RuntimeException e) { log("BLE 主动扫描启动失败：" + e.getClass().getSimpleName()); }
    }
    /** 更接近真实盒子的做法：不等手机广播，直接按已配对地址周期性发起 BLE 中心连接。
     *  手机对已绑定设备做 LE 地址解析，不需要它先广播。认证完成后自动停止。 */
    private void startGattDirectLoop(final int token) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!continuous || !running || generation.get() != token) return;
                TestSession session = activeSession;
                boolean authenticated = session != null && session.authenticated;
                if (!authenticated && (gattProbe == null || !gattProbe.busy()) && adapter != null && peerMac != null) {
                    try {
                        final int businessChannel = businessChannel();
                        log("GATT 主动连接：不等广播，直接连已配对手机 " + peerMac + "（业务通道 " + businessChannel + "）。");
                        gattProbe = new PeerGattProbe(context, localId, businessChannel,
                                gattCcmpEnabled(), new PeerGattProbe.Sink() {
                            @Override public void log(String message) { ReceiverEngine.this.log(message); }
                            @Override public void endpoint(int channel) {
                                ReceiverEngine.this.log("GATT CCMP：手机返回业务通道 " + channel + "；等待手机连回车机 FE36（通道 " + businessChannel + "）。");
                            }
                        });
                        gattProbe.connect(adapter.getRemoteDevice(peerMac));
                    } catch (RuntimeException e) {
                        log("GATT 主动连接失败：" + e.getClass().getSimpleName());
                    }
                }
                handler.postDelayed(this, 20000);
            }
        }, 3000);
    }
    private void stopPeerScan() {
        android.bluetooth.le.BluetoothLeScanner scanner;
        android.bluetooth.le.ScanCallback callback;
        synchronized (this) { scanner = peerScanner; callback = peerScanCallback; peerScanner = null; peerScanCallback = null; }
        if (scanner != null && callback != null) { try { scanner.stopScan(callback); } catch (RuntimeException ignored) { } }
    }
    /** 一条广播是否带 HiCar 特征：FDEE service data，或华为厂商数据（0x027D）。 */
    private static boolean looksLikeHicar(android.bluetooth.le.ScanRecord record) {
        if (record == null) return false;
        if (record.getServiceData() != null) {
            for (android.os.ParcelUuid uuid : record.getServiceData().keySet())
                if (uuid.toString().toLowerCase(java.util.Locale.ROOT).startsWith("0000fdee")) return true;
        }
        if (record.getManufacturerSpecificData() != null) {
            for (int i = 0; i < record.getManufacturerSpecificData().size(); i++)
                if (record.getManufacturerSpecificData().keyAt(i) == 0x027D) return true;
        }
        return false;
    }
    private String describeScan(android.bluetooth.le.ScanResult result) {
        StringBuilder sb = new StringBuilder("rssi=").append(result.getRssi());
        android.bluetooth.le.ScanRecord record = result.getScanRecord();
        if (record != null) {
            String name = record.getDeviceName();
            if (name != null && !name.isEmpty()) sb.append(" name=").append(name);
            if (record.getServiceUuids() != null && !record.getServiceUuids().isEmpty())
                sb.append(" uuids=").append(record.getServiceUuids());
            if (record.getServiceData() != null && !record.getServiceData().isEmpty()) {
                sb.append(" serviceData=");
                for (java.util.Map.Entry<android.os.ParcelUuid, byte[]> entry : record.getServiceData().entrySet())
                    sb.append(entry.getKey()).append(':').append(hexOf(entry.getValue())).append(' ');
            }
            if (record.getManufacturerSpecificData() != null && record.getManufacturerSpecificData().size() > 0) {
                for (int i = 0; i < record.getManufacturerSpecificData().size(); i++)
                    sb.append(" mfr=").append(record.getManufacturerSpecificData().keyAt(i)).append(':')
                            .append(hexOf(record.getManufacturerSpecificData().valueAt(i)));
            }
        }
        return sb.toString();
    }
    private static String hexOf(byte[] data) {
        if (data == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) { if (sb.length() > 0) sb.append(' '); sb.append(String.format(java.util.Locale.ROOT, "%02X", b & 255)); }
        return sb.toString();
    }
    private void startDiscoveryOrSilentReconnect(int token, byte[] payload) {
        if (generation.get() != token) return;
        startPeerScan(token);
        if (scanTriggerEnabled()) {
            log("扫描触发模式：正常广播 + 扫描已配对手机的 FDEE 广播；看到手机广播后立刻拨它的 FE35（不再盲拨）。");
            startFdeeAdvertising(token, payload);
            return;
        }
        if (quietMode()) {
            log("安静模式：立即启动 FDEE 广播，只监听 FE35/FE36，不主动拨号、不扫通道（模仿真实盒子上电即可被发现）。");
            startFdeeAdvertising(token, payload);
            return;
        }
        final boolean profile = profileReconnectEnabled();
        final boolean initiator = ccmpInitiatorEnabled();
        final boolean experiment = initiator || profile;
        /* 只有持续接收模式才进入静默回连；一次性测试（120 秒）仍保持原来的直接广播行为。 */
        final boolean silentReconnect = continuous && peerMac != null && experiment;
        if (!silentReconnect) { startFdeeAdvertising(token, payload); return; }
        final int window = profile ? PROFILE_WINDOW_MS : (initiator ? INITIATOR_WINDOW_MS : SILENT_RECONNECT_MS);
        silentWindowConnected = false;
        log("检测到已配对手机 " + peerMac + "：进入静默重连（" + window / 1000
                + " 秒内不开 FDEE 广播，模仿盒子 reconnecting 态，避免手机发现弹窗），"
                + (profile ? "系统 profile 主动回连实验已启用：反射 A2DP/HEADSET connect() 并拨标准 profile RFCOMM，保持 ACL。"
                           : initiator ? "无感回连已启用：车机主动发 start 帧并协商 business 通道。" : "仅主动敲门。"));
        maybeProbeReconnect(token);
        handler.postDelayed(() -> {
            if (generation.get() != token) return;
            if (silentWindowConnected) { log("静默重连已成功建立连接，无需广播。"); return; }
            log("静默重连窗口超时且手机未连回，启动 FDEE 广播兜底（回到可被发现态）。");
            startFdeeAdvertising(token, payload);
        }, window);
    }
    /** 停止 FDEE 广播。已配对手机回连时先关广播，避免手机把它当成「靠近发现」的新设备而弹窗；
     *  主动回连成功认证后保持关闭，直到本轮会话再次断开。 */
    private void stopAdvertising() {
        BluetoothLeAdvertiser current;
        AdvertiseCallback callback;
        synchronized (this) {
            current = advertiser; callback = advCallback;
            advertiser = null; advCallback = null;
        }
        if (current != null && callback != null) {
            try { current.stopAdvertising(callback); } catch (RuntimeException ignored) { }
        }
    }
    private void startFdeeAdvertising(int token, byte[] payload) {
        if (generation.get() != token) return;
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { log("BLE 广播器不可用，FDEE 无法发送。"); fail(token,"蓝牙广播不可用，请检查蓝牙状态后重试。"); return; }            final BluetoothLeAdvertiser thisAdvertiser = advertiser;
            advCallback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings settings) {
                    if (generation.get() == token) {StringBuilder hb=new StringBuilder();for(byte b:payload){if(hb.length()>0)hb.append(' ');hb.append(String.format(Locale.ROOT,"%02X",b&255));}log("FDEE 广播启动成功："+payload.length+" 字节 ["+hb+"]；这不是 HiCar 连接成功。");status.advance(token,ReceiverStatus.Stage.WAITING);maybeProbeReconnect(token);}
                    else try { thisAdvertiser.stopAdvertising(this); } catch (RuntimeException ignored) { }
                }
                @Override public void onStartFailure(int error) {
                    if (generation.get() == token) { log("FDEE 广播失败：" + advertiseError(error)); fail(token,"蓝牙发现广播启动失败，请稍后重新连接。"); }
                }
            };
            advertiser.startAdvertising(new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true).setTimeout(0).build(),
                    new AdvertiseData.Builder()
                            .addServiceUuid(new ParcelUuid(FDEE))
                            .addServiceData(new ParcelUuid(FDEE), payload).build(), advCallback);
        } catch (RuntimeException e) { log("FDEE 广播异常：" + e.getClass().getSimpleName()); fail(token,"蓝牙发现启动失败，请检查附近设备权限。"); }
    }
    /** 读取 RFCOMM 服务端实际绑定的通道号；Android 各版本暴露位置不同，失败返回 -1。
     *  手机静默回连时会直连上一次缓存的通道，通道号每次重启是否变化需要能看见。 */
    private int resolveServerChannel(BluetoothServerSocket server) {
        int resolved = -1;
        try {
            Object value = server.getClass().getMethod("getChannel").invoke(server);
            if (value instanceof Integer) resolved = RfcommPort.valid((Integer)value);
            if (resolved > 0) log("RFCOMM 端口获取：getChannel = " + resolved + "。");
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        if (resolved < 1) {
            try {
                java.lang.reflect.Field transport = server.getClass().getDeclaredField("mSocket");
                transport.setAccessible(true);
                Object socket = transport.get(server);
                try {
                    java.lang.reflect.Method port = socket.getClass().getDeclaredMethod("getPort");
                    port.setAccessible(true);
                    Object value = port.invoke(socket);
                    if (value instanceof Integer) resolved = RfcommPort.valid((Integer)value);
                } catch (ReflectiveOperationException | RuntimeException methodUnavailable) {
                    java.lang.reflect.Field port = socket.getClass().getDeclaredField("mPort");
                    port.setAccessible(true);
                    resolved = RfcommPort.valid(port.getInt(socket));
                }
                if (resolved > 0) log("RFCOMM 端口获取：底层 socket 端口 = " + resolved + "。");
            } catch (ReflectiveOperationException | RuntimeException e) { log("底层端口读取未获支持：" + e.getClass().getSimpleName()); }
        }
        if (resolved < 1) {
            resolved = RfcommPort.fromDescription(server.toString());
            if (resolved > 0) log("RFCOMM 端口获取：socket 描述 Channel 字段 = " + resolved + "（待真实连接验证）。");
        }
        return resolved;
    }
    private void startProtocol(int token, String model,boolean withVideo, Runnable onReady) {
        new Thread(() -> {
            try {
                BluetoothServerSocket business = adapter.listenUsingInsecureRfcommWithServiceRecord("HiCarLab Business", UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"));
                servers.add(business);
                int resolved = resolveServerChannel(business);
                if (resolved < 1) { log("系统未提供可核验的 RFCOMM 端口；尚未启动广播，未猜测或固定端口。"); fail(token,"这台设备暂未提供所需蓝牙服务，请查看诊断页。"); return; }
                final int channel = resolved;
                BluetoothServerSocket control = adapter.listenUsingInsecureRfcommWithServiceRecord("NearbyServerSocket", UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb"));
                servers.add(control);
                if (generation.get() != token) { business.close(); control.close(); return; }
                final int controlChannel = resolveServerChannel(control);
                log("FE35 / FE36 已注册；FE35 控制通道 " + (controlChannel > 0 ? String.valueOf(controlChannel) : "未知")
                        + "，FE36 业务通道 " + channel + "，等待手机连接。");
                final TestSession session = new TestSession(model,withVideo); sessions.add(session);
                session.network = networkResource;
                // 记录当前会话上下文，供断线后车机主动回连手机复用 handshake 链路
                activeSession = session; activeToken = token; activeModel = model; activeChannel = channel;
                if (session.network != null) startTcpListeners(session, token, model);
                new Thread(() -> acceptProtocol(business, token, model, channel, false, session), "lab-business").start();
                handler.post(() -> { if (generation.get() == token) onReady.run(); });
                acceptProtocol(control, token, model, channel, true, session);
            } catch (Exception e) { if (generation.get() == token) { log("协议监听启动失败：" + e.getClass().getSimpleName()); fail(token,"蓝牙连接服务启动失败，请重新连接。"); } }
        }, "lab-protocol").start();
    }
    private void acceptProtocol(BluetoothServerSocket server, int token, String model, int channel, boolean control, TestSession session) {
        try {
            while (generation.get() == token) {
                BluetoothSocket socket = server.accept();
                if (generation.get() != token) { try { socket.close(); } catch (IOException ignored) {} continue; }
                if (clients.size() >= 6) { socket.close(); continue; }
                handleConnectedSocket(socket, token, model, channel, control, session);
            }
        } catch (IOException | RuntimeException e) { if (generation.get() == token) { log("协议监听结束：" + e.getClass().getSimpleName()); requestReconnect(token,model); } }
    }
    /** 处理一个已建立的蓝牙连接（无论来自 accept 还是车机主动回连），统一走完整 HiCar 握手。 */
    private void handleConnectedSocket(BluetoothSocket socket, int token, String model, int channel, boolean control, TestSession session) {
        if (generation.get() != token) { closeQuietly(socket); return; }
        if (clients.size() >= 6) { closeQuietly(socket); return; }
        clients.add(socket);
        if (generation.get() != token) { closeQuietly(socket); clients.remove(socket); return; }
        if (!session.select(socket)) { log("拒绝其他设备加入当前实验会话；未记录地址。"); closeQuietly(socket); clients.remove(socket); return; }
        silentWindowConnected = true;   // 静默重连窗口内任何真实连接进入即视为成功，阻止兜底广播
        if (!control && !authBusy.compareAndSet(false, true)) { log("已有认证会话正在处理或清理，暂未接收新的业务连接。"); closeQuietly(socket); clients.remove(socket); return; }
        if(!control)status.advance(token,ReceiverStatus.Stage.VERIFYING);
        log((control ? "FE35" : "FE36") + " 建立连接；对端尚未认证。");
        new Thread(() -> protocolReceive(socket, token, model, channel, control, session), "lab-protocol-rx").start();
    }
    private static void closeQuietly(BluetoothSocket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
    /** 仅用于实验室协议诊断：把帧载荷压成有界 hex，便于比对手机应答格式。 */
    private static String hex(byte[] b,int max){StringBuilder s=new StringBuilder();for(int i=0;i<b.length&&i<max;i++){if(i>0)s.append(' ');s.append(String.format(Locale.ROOT,"%02X",b[i]&255));}if(b.length>max)s.append(" ...");return s.toString();}
    private void protocolReceive(BluetoothSocket socket, int token, String model, int channel, boolean control, TestSession session) {
        AuthBusiness auth = null;
        try {
            byte[] buffer = new byte[4096]; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            NearbyWire.Decoder decoder = new NearbyWire.Decoder();
            NearbyWire.Negotiator negotiation = new NearbyWire.Negotiator(localId.getBytes(StandardCharsets.US_ASCII), channel);
            if (!control) auth = new AuthBusiness(localId, localPin, model, new AuthBusiness.Events() {
                @Override public void log(String message) { if (generation.get() == token) ReceiverEngine.this.log(message); }
                @Override public void authenticated(byte[] key) { synchronized(session){if (generation.get() == token) { session.setKey(key); session.authenticated = true; silentWindowConnected = true; handler.post(ReceiverEngine.this::stopAdvertising); status.advance(token,ReceiverStatus.Stage.NETWORK); if (session.network != null) session.network.authenticated = true; if (session.peer != null && !session.peer.equalsIgnoreCase(peerMac)) { peerMac = session.peer; context.getSharedPreferences("identity", 0).edit().putString("peer_mac", peerMac).apply(); log("已记住配对手机蓝牙地址 " + peerMac + "，后续将仅接受该设备自动重连。"); } }} }
            });
            while (generation.get() == token) {
                int n = in.read(buffer); if (n < 0) break;
                if (control) {
                    for (NearbyWire.Frame frame : decoder.feed(buffer, n)) {
                        byte[] reply;
                        if (frame.kind == 4 || frame.kind == 33) {
                            if (!session.authenticated) throw new IOException("network before authentication");
                            if (session.network != null) { replyNetwork(frame, session.network, out); reply = null; }
                            else { inspectNetworkRequest(frame); reply = null; }
                        } else reply = negotiation.respond(frame);
                        if (reply != null) { out.write(reply); out.flush(); }
                        if (generation.get() == token) log("FE35 控制帧：类型 " + frame.kind + "，载荷 " + frame.payload.length + " 字节，响应 " + (reply == null ? 0 : reply.length) + " 字节。");
                    }
                } else {
                    if (!auth.done()) auth.feed(buffer, n, out);
                    else if (generation.get() == token) log("FE36 认证后收到 " + n + " 字节；未记录内容，等待后续实现。");
                }
            }
        } catch (Exception e) { if (generation.get() == token) log((control ? "FE35 协商" : "FE36 认证") + " 结束：" + e.getClass().getSimpleName() + "；未确认成功时请勿视为已连接。"); }
        finally {
            if (auth != null) auth.close(); if (!control) authBusy.set(false);
            if(!control && !session.authenticated && generation.get()==token){
                if(auth!=null && auth.wantsRebindHint()){
                    /* 免输码失败时绝不能自动轮换身份：手机侧保存的是本车机的 authId 与
                     * 长期公钥，换 id/pin 会让已建立的信任凭据彻底失效，之后再无免密可能。
                     * 保持身份不变，手机可随时重试；确需重绑由用户在设置页手动操作。 */
                    status.issue(token,"手机已配对但免输码验证未通过；已保持车机身份与凭据不变，等待手机重试。如需重绑，请在手机「设置 › 更多连接 › HiCar智行」删除本车机后重新输码配对。");
                }
                else status.issue(token,"连接验证未完成，请在手机重试，或点重新连接。");
            }
            clients.remove(socket); try { socket.close(); } catch (IOException ignored) { }
        }
    }
    private void inspectNetworkRequest(NearbyWire.Frame frame) throws IOException {
        if ((frame.flags & 1) != 0) throw new IOException("encrypted network control unsupported");
        for (NearbyWire.Field f : NearbyWire.fields(frame.payload)) {
            log("认证后网络请求：类型 " + frame.kind + "，字段 " + f.tag + "，长度 " + f.value.length + "。尚未回复网络能力。");
            if (frame.kind == 4 && f.tag == 1) {
                NearbyWire.Cursor c = new NearbyWire.Cursor(f.value, 0);
                try {
                    int channel = c.uint();
                    if (channel != 3) continue;
                    for (NearbyWire.Field detail : NearbyWire.fields(java.util.Arrays.copyOfRange(f.value, c.p, f.value.length))) {
                        if (detail.tag != 3 || detail.value.length > 256) continue;
                        NearbyWire.Cursor caps = new NearbyWire.Cursor(detail.value, 0);
                        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                        while (caps.p < detail.value.length) {
                            int key = caps.uint(), value = caps.uint();
                            if (!seen.add(key) || seen.size() > 32) throw new IOException("network capability fields");
                            log("手机网络能力字段 " + key + " = " + value + "（仅记录能力数值）。");
                        }
                    }
                } catch (NearbyWire.Short e) { throw new IOException("network request truncated"); }
            }
        }
    }
    private void replyNetwork(NearbyWire.Frame frame, NetworkResource resource, OutputStream out) throws IOException {
        synchronized (networkLock) {
            byte[] reply = resource.respond(frame);
            if (reply != null) { out.write(reply); out.flush(); }
            for (byte[] pending : resource.takePending()) { out.write(pending); out.flush(); }
            log("网络协商：类型 " + frame.kind + "，响应 " + (reply == null ? 0 : reply.length) + " 字节；角色已协商 " + resource.negotiated + "，临时凭据已发送 " + resource.credentialsSent + "。");
        }
    }
    private void startTcpListeners(TestSession session, int token, String model) throws IOException {
        NetworkResource resource = session.network;
        for (int mode = 0; mode < 3; mode++) {
            final int kind = mode, port = resource.offer.controlPort + mode;
            java.net.ServerSocket server = new java.net.ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(resource.offer.ownerIp(), port), 4);
            tcpServers.add(server);
            new Thread(() -> acceptTcp(server, session, token, kind), "lab-network-" + mode).start();
        }
        resource.dataPort = resource.offer.controlPort + 1;
        log("本轮热点的三个 TCP 监听已就绪；尚未收到手机网络连接。");
    }
    private void acceptTcp(java.net.ServerSocket server, TestSession session, int token, int kind) {
        try {
            while (generation.get() == token) {
                java.net.Socket socket = server.accept();
                if (generation.get() != token || !session.authenticated || !session.network.credentialsSent || tcpClients.size() >= 8
                        || !socket.getInetAddress().getHostAddress().equals(session.network.offer.clientIp())) { socket.close(); log("拒绝未选定或未认证的 TCP 连接。"); continue; }
                if(kind==2&&!session.controlBusy.compareAndSet(false,true)){socket.close();log("已有本轮 DMSDP 控制连接，拒绝重复连接。");continue;}
                if(kind==2)status.advance(token,ReceiverStatus.Stage.MEDIA);
                tcpClients.add(socket);
                log("收到手机 TCP 连接：阶段 " + kind + "（0 网络控制，1 DMSDP 入口，2 DMSDP 控制）。");
                new Thread(() -> receiveTcp(socket, session, token, kind), "lab-tcp-rx").start();
            }
        } catch (IOException e) { if (generation.get() == token) {log("TCP 监听结束：I/O 错误。"); requestReconnect(token,session.model); } }
    }
    private void receiveTcp(java.net.Socket socket, TestSession session, int token, int kind) {
        ControlSession controlSession=null;AudioReceiver audioReceiver=null;MicrophoneSender microphone=null;
        try {
            socket.setSoTimeout(kind==2?250:1000);
            byte[] buffer = new byte[4096]; int requests = 0;
            InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
            NearbyWire.Decoder decoder = new NearbyWire.Decoder();
            NearbyWire.Negotiator negotiator = new NearbyWire.Negotiator(localId.getBytes(StandardCharsets.US_ASCII), -1);
            DmsdpWire.Decoder dmsdp = new DmsdpWire.Decoder();
            long lastPeer=System.nanoTime(),lastHeartbeat=lastPeer;
            if(kind==2){
                byte[] key=session.copyKey();if(key==null)throw new IOException("missing authenticated key");
                if(session.video){AudioSink sink=null;try{
                    sink=new AudioSink(context);audioReceiver=new AudioReceiver(session.network.offer.ownerIp(),session.network.offer.clientIp(),key,sink,new AudioReceiver.Events(){public boolean active(){return generation.get()==token;}public void log(String text){if(active())ReceiverEngine.this.log(text);}});audioReceivers.add(audioReceiver);
                }catch(IOException|RuntimeException e){if(sink!=null)sink.close();log("本轮音频后端未就绪："+e.getClass().getSimpleName()+"；继续画面与触控。");}}
                if(session.video&&microphoneAllowed&&MicrophoneSender.enabled(context))try{
                    microphone=new MicrophoneSender(context,session.network.offer.ownerIp(),session.network.offer.clientIp(),key,new MicrophoneSender.Events(){public boolean active(){return generation.get()==token;}public void log(String text){if(active())ReceiverEngine.this.log(text);}});microphones.add(microphone);
                }catch(IOException|RuntimeException e){log("麦克风实验初始化失败："+e.getClass().getSimpleName()+"；继续画面与播放。");}
                final MicrophoneSender selectedMicrophone=microphone;
                final MicrophoneControl micControl=microphone==null?null:new MicrophoneControl(microphone);
                final AudioReceiver selectedAudio=audioReceiver;
                final SpeakerControl speakerControl=selectedAudio==null?null:new SpeakerControl(selectedAudio);
                final ControlSession.Events controlEvents=new ControlSession.Events(){public void log(String text){if(generation.get()==token)ReceiverEngine.this.log(text);}public void clock(long high,long low){if(selectedMicrophone!=null)selectedMicrophone.clock(high,low);}};
                final ControlSession.TextHandler audioControl=(start,headers,body)->{List<byte[]> replies=micControl==null?null:micControl.receive(start,headers,body);return replies!=null?replies:speakerControl==null?null:speakerControl.receive(start,headers,body);};
                try{controlSession=new ControlSession(key,controlEvents,new DeviceBusiness(localId,session.model,session.network.offer.ownerIp(),session.network.offer.clientIp(),this::log,new DeviceBusiness.Display(){
                    public boolean ready(){return session.video&&((LabApplication)context).video().ready()&&generation.get()==token;}
                    public void endpoint(int port)throws IOException{startHiSight(session,token,port);}
                },selectedAudio,selectedMicrophone),audioControl);}finally{Arrays.fill(key,(byte)0);}
                output.write(controlSession.initial());output.flush();log("DMSDP OPTIONS 首包已发送，使用本轮认证密钥协商 AES-CTR；未发送媒体能力。");
            }
            while (generation.get() == token) {
                if(kind==2&&controlSession.negotiated()&&System.nanoTime()-lastHeartbeat>1000000000L){output.write(controlSession.heartbeat());output.flush();lastHeartbeat=System.nanoTime();}
                int n;
                try{n=input.read(buffer);}catch(java.net.SocketTimeoutException e){
                    if(kind==2){long now=System.nanoTime();if(now-lastPeer>30000000000L)throw e;
                        if(controlSession.negotiated()&&now-lastHeartbeat>1000000000L){output.write(controlSession.heartbeat());output.flush();lastHeartbeat=now;}
                    }
                    continue;
                }
                if (n < 0) break; lastPeer=System.nanoTime();
                if (kind == 0) for (NearbyWire.Frame frame : decoder.feed(buffer, n)) {
                    if (frame.kind == 4 || frame.kind == 33) replyNetwork(frame, session.network, output);
                    else { byte[] reply = negotiator.respond(frame); if (reply != null) { output.write(reply); output.flush(); } }
                    log("TCP 网络控制帧：类型 " + frame.kind + "，载荷 " + frame.payload.length + " 字节。");
                } else if (kind == 1) for (DmsdpWire.Packet packet : dmsdp.feed(buffer, n)) {
                    if (++requests > 3) throw new IOException("DMSDP request limit");
                    output.write(DmsdpWire.controlOffer(packet, session.network.offer.controlPort + 2)); output.flush();
                    log("DMSDP 入口协商已响应；等待独立控制连接。");
                } else {for(byte[] reply:controlSession.feed(buffer,n)){output.write(reply);output.flush();}}
            }
        } catch (IOException | java.security.GeneralSecurityException e) { if (generation.get() == token) log("TCP 阶段 " + kind + " 结束：" + e.getClass().getSimpleName()); }
        finally {if(microphone!=null){microphone.close();microphones.remove(microphone);}if(audioReceiver!=null){audioReceiver.close();audioReceivers.remove(audioReceiver);}if(controlSession!=null)controlSession.close();if(kind==2)session.controlBusy.set(false);tcpClients.remove(socket); try {socket.close();} catch (IOException ignored) {}if(kind==2)requestReconnect(token, session.model);}
    }
    private void startHiSight(TestSession session,int token,int port)throws IOException{
        VideoSink sink=((LabApplication)context).video();
        if(session.video && !sink.ready()){
            if(!sink.prepareBackground()) log("视频解码器重新准备失败；画面将在手机重连后恢复。");
        }
        if(!session.video||!sink.ready()||generation.get()!=token||!session.mediaBusy.compareAndSet(false,true))throw new IOException("video state");
        byte[] key=session.copyKey();if(key==null)throw new IOException("video key unavailable");
        final HiSightClient client;
        try{client=new HiSightClient(session.network.offer.ownerIp(),session.network.offer.clientIp(),key,sink,new HiSightClient.Events(){
            public boolean active(){return generation.get()==token;}
            public void log(String text){if(active())ReceiverEngine.this.log(text);}
            public void add(java.net.Socket s){tcpClients.add(s);}
            public void remove(java.net.Socket s){tcpClients.remove(s);}
        },((LabApplication)context).input());}finally{Arrays.fill(key,(byte)0);}
        videoClients.add(client);new Thread(()->{try{client.run(port);}finally{videoClients.remove(client);session.mediaBusy.set(false);requestReconnect(token,session.model);}},"lab-hisight-control").start();
    }
    /** 车机主动回连已配对手机的入口（对标官方 startReconnect）：持续接收模式且已记住手机 MAC 时，
     *  静默窗口开始或广播兜底就绪后向手机发起 RFCOMM 回连；手机因已配对而免弹窗、直接 STS 0x11 恢复。
     *  默认开启；设置页关闭「车机主动回连」后仅保留广播等待。 */
    /** 诊断用：安静模式。只保持 FDEE 广播并监听 FE35/FE36，不主动拨手机、不扫 RFCOMM 通道，
     *  用于验证「车机主动敲门」是否就是手机弹「靠近发现」确认卡片的原因。默认关闭。 */
    private boolean quietMode(){return context.getSharedPreferences("ui",0).getBoolean("quiet_mode",false);}
    private void maybeProbeReconnect(int token) {
        if (!continuous || generation.get() != token || peerMac == null) return;
        if (quietMode()) return;   /* 安静模式：不拨号、不扫通道，只广播与监听 */
        if (scanTriggerEnabled()) return;   /* 扫描触发模式：等看到手机广播后再拨号 */
        if (!context.getSharedPreferences("ui", 0).getBoolean("auto_probe_reconnect", true)) return;
        if (profileReconnectEnabled()) { startProfileReconnect(token); return; }
        startReconnectToPeer();
    }
    /** 系统 profile 主动回连实验：静默窗口内不广播，反射系统 A2DP/HEADSET 的隐藏 connect()
     *  并拨标准 profile RFCOMM 保持 ACL，目标是让手机走 reconnectType 2 自己连回 FE35。
     *  默认关闭；失败只记日志，窗口结束后 FDEE 兜底广播照常启动，稳定路径不变。 */
    private void startProfileReconnect(final int token) {
        /* 广播兜底后 startFdeeAdvertising 会再次触发 maybeProbeReconnect；
         * 同一轮只跑一次，否则会重复拨号、重复关闭上一轮保持的 ACL 链路。 */
        if (profileTriedToken == token) return;
        profileTriedToken = token;
        final String mac = peerMac;
        if (mac == null || adapter == null) return;
        new Thread(() -> {
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            log("系统 profile 回连实验启动：目标 " + mac
                    + "；静默窗口内不开启 FDEE 广播。若手机在广播前主动连回 FE35，即为 reconnectType 2 生效。");
            List<BluetoothSocket> keep = ProfileReconnect.attempt(context, device, this::log);
            for (BluetoothSocket socket : keep) clients.add(socket);
            long deadline = System.currentTimeMillis() + PROFILE_WINDOW_MS;
            while (generation.get() == token && running && System.currentTimeMillis() < deadline) {
                if (silentWindowConnected) {
                    log("系统 profile 回连实验：手机已在静默窗口内主动连回，未开启 FDEE 广播（零弹窗 + 免输码成立）。");
                    break;
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (!silentWindowConnected)
                log("系统 profile 回连实验结束：静默窗口内手机未主动连回；保持的 ACL 链路即将关闭。");
            for (BluetoothSocket socket : keep) {
                clients.remove(socket);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }, "lab-profile-reconnect").start();
    }
    /** 车机主动回连已配对手机。
     *
     *  默认走 HelloCar radio.rs 的 reconnect 路径：查手机 FE35 的 SDP 通道 → 拨通 →
     *  车机作为 CCMP initiator 主动发 start 帧 → 协商出手机的 business 通道 → 拨 FE36
     *  完成 STS 免密认证。手机侧始终处于「已配对设备回连」状态，不需要点「靠近发现」。
     *
     *  关闭「无感回连」开关时保留旧的敲门路径（只建立 ACL，等手机自己连回）。
     */
    private void startReconnectToPeer() {
        final String mac = peerMac;
        final TestSession session = activeSession;
        final int token = activeToken;
        final String model = activeModel;
        final int channel = activeChannel;
        if (mac == null || adapter == null || session == null || !continuous
                || generation.get() != token) return;
        if (ccmpInitiatorEnabled()) startInitiatorReconnect(mac, token, model, channel, session);
        else startKnockReconnectToPeer(mac, token, model, channel, session);
    }
    /** 同一代次只跑一个主动回连循环；成功建立会话或代次变化后自动退出。 */
    private void startInitiatorReconnect(final String mac, final int token, final String model,
                                         final int channel, final TestSession session) {
        final int epoch;
        synchronized (this) {
            epoch = reconnectEpoch;
            if (reconnectLoopToken == token && reconnectLoopEpoch == epoch) return;
            reconnectLoopToken = token;
            reconnectLoopEpoch = epoch;
        }
        new Thread(() -> {
            try {
                BluetoothDevice d = adapter.getRemoteDevice(mac);
                int round = 0;
                while (reconnectStillWanted(token, epoch)) {
                    if (session.authenticated) {
                        log("主动回连：手机已完成认证，停止重复拨号。");
                        return;
                    }
                    round++;
                    java.util.LinkedHashSet<java.util.UUID> cands = controlCandidates(d);
                    log("无感回连第 " + round + " 轮：候选控制服务 " + cands
                            + "（FE35 优先，已排除标准 profile 与 FE36 业务通道）。");
                    for (java.util.UUID service : cands) {
                        if (!reconnectStillWanted(token,epoch)) return;
                        if (session.authenticated) return;
                        BluetoothSocket control = null;
                        try {
                            control = d.createInsecureRfcommSocketToServiceRecord(service);
                            control.connect();
                            if (!reconnectStillWanted(token,epoch)) { closeQuietly(control); return; }
                            log("无感回连：手机服务 " + service + " 已拨通，作为控制通道发送 CCMP start 帧。");
                            if (pumpControlInitiator(control, d, service, token, model, channel, session, epoch)) {
                                context.getSharedPreferences("identity", 0).edit()
                                        .putString("dial_uuid", service.toString()).apply();
                                return;   // 会话已由业务通道接管；下次断线会重新拉起循环
                            }
                        } catch (IOException | RuntimeException e) {
                            log("无感回连：服务 " + service + " 拨号失败（" + e.getClass().getSimpleName()
                                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）。");
                            if (control != null) closeQuietly(control);
                        }
                    }
                    /* Android 的 FE35 服务名解析可能命中 ATT 记录导致 RFCOMM 打不开；
                     * 按上游 sdptool 的语义直接枚举 RFCOMM 通道找控制通道。 */
                    if (round == 1 || round % 3 == 0) sweepControlChannels(d, token, model, channel, session, epoch);
                    long wait = round == 1 ? 3000L : round == 2 ? 5000L : 10000L;
                    log("无感回连第 " + round + " 轮未完成协商；" + (wait / 1000)
                            + " 秒后重试（期间保持静默，不启动 FDEE 广播）。");
                    try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            } catch (RuntimeException e) {
                log("无感回连循环结束：" + e.getClass().getSimpleName() + "。");
            } finally {
                synchronized (ReceiverEngine.this) {
                    if (reconnectLoopToken == token && reconnectLoopEpoch == epoch) {
                        reconnectLoopToken = -1;
                        reconnectLoopEpoch = -1;
                    }
                }
            }
        }, "lab-reconnect-initiator").start();
    }
    /** 控制通道候选：FE35 必须排第一；其次上次成功的缓存；最后手机 SDP 公布的非标准服务。
     *  FE36 是业务通道，不能拿它当控制通道发 CCMP start。 */
    private java.util.LinkedHashSet<java.util.UUID> controlCandidates(BluetoothDevice d) {
        java.util.LinkedHashSet<java.util.UUID> cands = new java.util.LinkedHashSet<>();
        cands.add(FE35);
        String cached = context.getSharedPreferences("identity", 0).getString("dial_uuid", null);
        if (cached != null && !cached.startsWith("channel:")) try {
            java.util.UUID u = java.util.UUID.fromString(cached);
            if (!isStandardProfileUuid(u) && !u.equals(UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"))) cands.add(u);
        } catch (IllegalArgumentException ignored) { }
        try {
            android.os.ParcelUuid[] us = d.getUuids();
            if (us != null) for (android.os.ParcelUuid u : us) if (u != null && u.getUuid() != null
                    && !isStandardProfileUuid(u.getUuid())
                    && !u.getUuid().equals(UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"))) cands.add(u.getUuid());
        } catch (Throwable ignored) { }
        return cands;
    }
    /** 真机兜底：FE35 的 SDP 记录里可能同时有 ATT 与 RFCOMM，Android 的
     *  createRfcommSocketToServiceRecord(FE35) 实测会命中 ATT 记录而打不开 RFCOMM。
     *  这里按上游 sdptool 的语义直接枚举 RFCOMM 通道，在可连通通道上发 CCMP start 探测，
     *  找到真正监听 HiCar 控制协议的那一个。 */
    private boolean sweepControlChannels(BluetoothDevice d,int token,String model,int channel,
                                         TestSession session,int epoch){
        if(!chanSweepEnabled()||!reconnectStillWanted(token,epoch)||session.authenticated)return false;
        java.lang.reflect.Method chanDial;
        try{chanDial=BluetoothDevice.class.getMethod("createInsecureRfcommSocket",int.class);}
        catch(ReflectiveOperationException e){log("RFCOMM 通道扫描不可用（"+e.getClass().getSimpleName()+"）。");return false;}
        StringBuilder reachable=new StringBuilder();
        int[] order={5,6,7,8,9,10,11,12,13,3,14,15,2,16,17,18,1,19,20,21,22,23,24,25,26,27,28,29,30};
        for(int ch:order){
            if(!reconnectStillWanted(token,epoch)||session.authenticated)return false;
            if(ch==4)continue;   // 实测通道 4 是手机 SPP，收到 CCMP 也不回
            BluetoothSocket s=null;
            try{
                s=(BluetoothSocket)chanDial.invoke(d,ch);
                s.connect();
                if(reachable.length()>0)reachable.append(',');
                reachable.append(ch);
                NearbyWire.Negotiator neg=new NearbyWire.Negotiator(localId.getBytes(StandardCharsets.US_ASCII),channel);
                if(passiveBusiness())neg.deferOwnBusinessRequest();
                OutputStream out=s.getOutputStream();
                out.write(NearbyWire.pack(0,neg.start()));out.flush();
                if(awaitProtocolBytes(s,2000)>0){
                    log("RFCOMM 通道 "+ch+" 有控制协议响应，按 FE35 控制通道执行 CCMP initiator。");
                    if(pumpControlInitiator(s,d,FE35,token,model,channel,session,epoch,neg))return true;
                }else{
                    log("RFCOMM 通道 "+ch+" 可连通但 2 秒内无协议响应，继续扫描。");
                }
            }catch(IOException|RuntimeException|ReflectiveOperationException e){
                // 未监听的通道直接跳过，不逐条刷日志
            }finally{
                if(s!=null&&!clients.contains(s))closeQuietly(s);
            }
        }
        log("RFCOMM 通道扫描完毕：本轮可连通=["+reachable+"]；未发现回 CCMP 的控制通道。");
        return false;
    }
    /** 在一个已拨通的控制通道上执行 CCMP initiator 握手；成功返回 true（会话已交给 FE36 认证）。 */
    private boolean pumpControlInitiator(BluetoothSocket control, BluetoothDevice d, java.util.UUID service,
                                         int token, String model, int channel, TestSession session, int epoch) {
        return pumpControlInitiator(control, d, service, token, model, channel, session, epoch, null);
    }
    /** preNeg 非空表示 start 帧已经由调用方发出（通道扫描探测），不要再发第二遍。 */
    private boolean pumpControlInitiator(BluetoothSocket control, BluetoothDevice d, java.util.UUID service,
                                         int token, String model, int channel, TestSession session, int epoch,
                                         NearbyWire.Negotiator preNeg) {
        clients.add(control);
        initiatorControl = control;
        try {
            InputStream in = control.getInputStream();
            OutputStream out = control.getOutputStream();
            /* 控制通道同时携带本机 FE36 业务通道号：HiCar 正常方向是手机连回车机 FE36。
             * preNeg 非空时表示 start 已由通道扫描发出。 */
            NearbyWire.Negotiator neg = preNeg != null ? preNeg
                    : new NearbyWire.Negotiator(localId.getBytes(StandardCharsets.US_ASCII), channel);
            if (preNeg == null) {
                if (passiveBusiness()) neg.deferOwnBusinessRequest();
                byte[] first = NearbyWire.pack(0, neg.start());
                out.write(first); out.flush();
                log("无感回连：已向 " + service + " 发出 CCMP start 帧（" + first.length + " 字节），等待协商。");
            } else {
                log("无感回连：通道 " + service + " 的 CCMP start 已发出，继续等待协商。");
            }
            boolean done = pumpInitiator(control, in, out, neg, d, token, model, channel, session, service, epoch);
            if (done) log("无感回连：控制通道协商完成，FE36 业务认证已接管。");
            return done;
        } catch (Exception e) {
            log("无感回连：控制通道 " + service + " 结束（" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage()) + "）。");
            return false;
        } finally {
            if(initiatorControl==control)initiatorControl=null;
            clients.remove(control);
            closeQuietly(control);
        }
    }
    /** 旧的敲门回连路径（关闭无感回连开关时使用）：
     *  只建立 ACL 唤醒手机 HiCar 栈，然后等待手机主动连回本端 FE35/FE36。
     *  拨上的通道若出现协议数据，按控制通道执行 CCMP initiator，避免把控制帧误交给业务认证。 */
    private void startKnockReconnectToPeer(final String mac, final int token, final String model,
                                           final int channel, final TestSession session) {
        final int epoch = reconnectEpoch;
        new Thread(() -> {
            BluetoothDevice d = adapter.getRemoteDevice(mac);
            java.util.LinkedHashSet<java.util.UUID> cands = new java.util.LinkedHashSet<>();
            String cached = context.getSharedPreferences("identity", 0).getString("dial_uuid", null);
            if (cached != null && !cached.startsWith("channel:")) try { cands.add(java.util.UUID.fromString(cached)); } catch (IllegalArgumentException ignored) {}
            try {
                android.os.ParcelUuid[] us = d.getUuids();
                if (us != null) for (android.os.ParcelUuid u : us) if (u != null && u.getUuid() != null && !isStandardProfileUuid(u.getUuid())) cands.add(u.getUuid());
            } catch (Throwable ignored) {}
            cands.add(FE35);
            cands.add(java.util.UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"));
            log("车机主动敲门回连手机 " + mac + "：候选服务 " + cands.size() + " 个（已排除标准 profile）。");
            java.lang.reflect.Method chanDial = null;
            try { chanDial = BluetoothDevice.class.getMethod("createInsecureRfcommSocket", int.class); } catch (Throwable ignored) {}
            for (int round = 1; round <= 8 && reconnectStillWanted(token,epoch); round++) {
                for (java.util.UUID u : cands) {
                    if (!reconnectStillWanted(token,epoch)) return;
                    BluetoothSocket s = null;
                    try {
                        s = d.createInsecureRfcommSocketToServiceRecord(u);
                        s.connect();
                        int waited = awaitProtocolBytes(s, 8000);
                        if (waited > 0) {
                            log("敲门通道 " + u + " 有协议数据；按控制通道尝试 CCMP initiator。");
                            if (pumpControlInitiator(s, d, u, token, model, channel, session, epoch)) return;
                            continue;
                        }
                        log("服务 " + u + " 已建立 ACL（敲门，8 秒无协议数据）；等待手机 HiCar 主动连回。");
                        closeQuietly(s);
                        if (awaitPeerRejoin(token, 6000)) { log("手机已在敲门后主动连回，回连线程退出。"); return; }
                    } catch (IOException | RuntimeException e) {
                        if (s != null) closeQuietly(s);
                    }
                }
                if (chanDial != null) {
                    for (int ch = 1; ch <= 20 && reconnectStillWanted(token,epoch); ch++) {
                        BluetoothSocket s = null;
                        try {
                            s = (BluetoothSocket) chanDial.invoke(d, ch);
                            s.connect();
                            int waited = awaitProtocolBytes(s, 5000);
                            if (waited > 0) {
                                log("RFCOMM 通道 " + ch + " 有协议数据；按控制通道尝试 CCMP initiator。");
                                if (pumpControlInitiator(s, d, java.util.UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb"),
                                        token, model, channel, session, epoch)) return;
                                continue;
                            }
                            log("RFCOMM 通道 " + ch + " 已建立 ACL（敲门，5 秒无协议数据）；继续扫描。");
                            closeQuietly(s);
                            if (awaitPeerRejoin(token, 6000)) { log("手机已在敲门后主动连回，回连线程退出。"); return; }
                        } catch (IOException | RuntimeException | ReflectiveOperationException e) {
                            if (s != null) closeQuietly(s);
                        }
                    }
                }
                if (round % 2 == 0) log("车机敲门回连第 " + round + " 轮完毕；持续重试。");
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }, "lab-reconnect-probe").start();
    }
    /** 一个已拨通通道的 CCMP initiator 收发循环：无响应 8 秒即换候选；有响应则持续协商，
     *  直到拿到 endpoint 并连上 FE36，之后控制通道继续服务到会话结束。 */
    private boolean pumpInitiator(BluetoothSocket control,InputStream in,OutputStream out,NearbyWire.Negotiator neg,
                                  BluetoothDevice d,int token,String model,int channel,TestSession session,UUID service,int epoch)throws IOException{
        NearbyWire.Decoder decoder=new NearbyWire.Decoder();
        byte[] buffer=new byte[4096];
        long deadline=System.currentTimeMillis()+8000;
        long passiveUntil=0;
        boolean fallbackRequested=false;
        boolean businessStarted=false,responded=false,advertStopped=false;
        while(generation.get()==token&&reconnectEpoch==epoch&&System.currentTimeMillis()<deadline){
            /* 业务通道一旦完成 STS 免密认证，立即关闭 FDEE 广播并用静默窗口标记成功：
             * 手机已恢复投影后不应再把它当成「靠近发现」的新设备。 */
            if(session.authenticated){
                silentWindowConnected=true;
                deadline=Long.MAX_VALUE;   // 认证后控制通道继续服务资源/网络帧
                if(!advertStopped){advertStopped=true;handler.post(this::stopAdvertising);}
            }
            int n;
            if(businessStarted){
                /* 会话稳定后用阻塞读服务控制帧，避免 20Hz 轮询空转；stopInternal 关闭
                 * clients 中的本 socket 时 read 会抛异常/返回 -1，线程随之退出。 */
                n=in.read(buffer);
                if(n<0)break;
            }else{
                int avail;
                try{avail=in.available();}catch(IOException e){break;}
                if(avail<=0){try{Thread.sleep(50);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}continue;}
                n=in.read(buffer);if(n<0)break;
            }
            for(NearbyWire.Frame frame:decoder.feed(buffer,n)){
                responded=true;
                log("CCMP initiator 收到帧（"+service+"）：kind="+frame.kind+" flags="+frame.flags
                        +" 载荷="+frame.payload.length+" 字节 ["+hex(frame.payload,64)+"]。");
                if((frame.kind==4||frame.kind==33)&&session.authenticated&&session.network!=null){
                    replyNetwork(frame,session.network,out);continue;
                }
                byte[] reply;
                try{reply=neg.respond(frame);}
                catch(java.io.IOException e){
                    log("CCMP initiator 协商拒绝（"+service+"）：kind="+frame.kind+" flags="+frame.flags
                            +" → "+e.getMessage()+"。");
                    throw e;
                }
                if(reply!=null){out.write(reply);out.flush();}
                List<byte[]> pending=neg.takePending();
                for(byte[] p:pending){out.write(p);out.flush();}
                int port=neg.endpoint();
                log("CCMP initiator 帧（"+service+"）：kind="+frame.kind+" 载荷="+frame.payload.length
                        +" 响应="+(reply==null?0:reply.length)+" 主动帧="+pending.size()
                        +" 完成="+neg.complete()+" endpoint="+port+"。");
                if(!businessStarted&&!pending.isEmpty()&&port<1)
                    log("CCMP initiator：已主动发出 business 请求（kind 2 / TLV 3），等待手机回 endpoint。");
                if(port>0&&!businessStarted){
                    businessStarted=openInitiatorBusiness(d,port,token,model,channel,session);
                    if(businessStarted){
                        silentWindowConnected=true;
                        deadline=Long.MAX_VALUE;
                        log("CCMP initiator：已连上手机 FE36（通道 "+port+"），交给免输码 STS；静默窗口无需广播。");
                    }
                }
                if(!businessStarted){
                    if(passiveBusiness()&&neg.complete()){
                        /* HiCar 正常方向：手机连回车机 FE36。先给手机 12 秒，
                         * 不主动索取手机端口（实测手机会回 result=1 拒绝）。 */
                        if(passiveUntil==0){
                            passiveUntil=System.currentTimeMillis()+12000;
                            deadline=passiveUntil;
                            log("CCMP initiator：控制协商完成，等待手机连回车机 FE36（通道 "+channel+"，12 秒）。");
                        }else if(!fallbackRequested&&System.currentTimeMillis()>=passiveUntil){
                            fallbackRequested=true;
                            neg.allowOwnBusinessRequest();
                            neg.requestOwnBusinessIfReady();
                            for(byte[] p:neg.takePending()){out.write(p);out.flush();}
                            deadline=System.currentTimeMillis()+8000;
                            log("CCMP initiator：手机未连回 FE36，改为主动索取手机 business 端口。");
                        }else{
                            deadline=Math.max(deadline,passiveUntil);
                        }
                    }else{
                        deadline=System.currentTimeMillis()+8000;  // 有响应则延长协商窗口
                    }
                }
            }
        }
        if(!responded)log("CCMP initiator：服务 "+service+" 拨通但 8 秒内对端未回任何协议帧。");
        return businessStarted;
    }
    /** 按协商出的 endpoint 主动连手机 FE36 business 通道，并交给既有免输码认证流程。 */
    private boolean openInitiatorBusiness(BluetoothDevice d,int port,int token,String model,int channel,TestSession session){
        if(authBusy.get()||clients.size()>=6){log("CCMP initiator：认证忙或连接数已满，跳过主动 business 连接。");return false;}
        java.lang.reflect.Method chanDial=null;
        try{chanDial=BluetoothDevice.class.getMethod("createInsecureRfcommSocket",int.class);}catch(Throwable ignored){}
        BluetoothSocket biz=null;
        try{
            if(chanDial!=null)biz=(BluetoothSocket)chanDial.invoke(d,port);
            else biz=d.createInsecureRfcommSocketToServiceRecord(UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"));
            biz.connect();
            log("CCMP initiator：手机 FE36 通道 "+port+" 已连接。");
            handleConnectedSocket(biz,token,model,channel,false,session);
            return true;
        }catch(Exception e){
            log("CCMP initiator：连手机 FE36 失败 "+e.getClass().getSimpleName()+"。");
            if(biz!=null)closeQuietly(biz);
            return false;
        }
    }
    /** 标准蓝牙 profile UUID 黑名单：连上也不会跑 HiCar 协议（OPP/SPP/A2DP/AVRCP/HFP/PAN 等），防止假阳性占用握手。 */
    private static boolean isStandardProfileUuid(java.util.UUID u) {
        String s = u.toString().toLowerCase(java.util.Locale.ROOT);
        if (!s.endsWith("-0000-1000-8000-00805f9b34fb")) return false;
        String p = s.substring(0, 8);
        switch (p) {
            case "00001101": case "00001105": case "0000110a": case "0000110b":
            case "0000110c": case "0000110e": case "00001112": case "00001115":
            case "00001116": case "0000111e": case "0000111f": case "00001124":
            case "0000112d": case "0000112f": case "00001132": case "00001200":
                return true;
            default:
                return false;
        }
    }
    /** 拨上的通道等待对端先发协议数据（HiCar 会话总是手机侧先发身份交换）；返回等待的毫秒数，0=超时无数据。 */
    private static int awaitProtocolBytes(BluetoothSocket s, int timeoutMs) {
        try {
            java.io.InputStream in = s.getInputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) return timeoutMs;
                Thread.sleep(100);
            }
        } catch (Exception ignored) {}
        return 0;
    }
    /** 敲门后短暂等待手机主动连回（FE36 监听在线）；返回期间是否已有新连接进入。 */
    private boolean awaitPeerRejoin(int token, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        while (System.currentTimeMillis() < deadline) {
            if (generation.get() != token || !continuous) return true;   // 状态变化即退出，让主流程接管
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
    /** 清除已配对手机地址与回连策略缓存，允许重新配对任意设备。 */
    void forgetPeer() {
        peerMac = null;
        context.getSharedPreferences("identity", 0).edit().remove("peer_mac").remove("dial_uuid").apply();
        log("已清除已配对手机地址与回连缓存；下次将接受任意手机首次连接。");
    }
    private void requestReconnect(int token,String model){
        if(!continuous || generation.get()!=token || !reconnect.request(token))return;
        handler.post(()->{
            if(!continuous || !running || generation.get()!=token)return;
            /* 认证成功后手机常会主动关闭 FE35/FE36，甚至重置单条 TCP 控制连接，投屏仍走 Wi-Fi。
             * 此时若照常清空会话（把 authenticated 置回 false）会打断正在进行的投屏，并在 25 秒后
             * 触发整轮重启。只要视频通道还在，就忽略这次套接字关闭。 */
            TestSession live=activeSession;
            if(live!=null && live.authenticated && !videoClients.isEmpty()){
                log("认证与视频通道都还在：忽略本次套接字关闭，不执行软重连。");
                reconnect.reset();
                return;
            }
            final boolean initiator=ccmpInitiatorEnabled();
            log("连接已断开：保留热点与 RFCOMM 监听不中断，仅重置本轮会话等待自动重连。");
            /* 无感回连：先关掉 FDEE 广播再主动拨号。手机扫不到「新设备」就不会弹
             * 「靠近发现」，只会按已配对设备回连。认证成功后广播保持关闭。 */
            if(initiator && !quietMode())stopAdvertising();
            resetSessionForReconnect(token);
            nextReconnectEpoch();     // 让上一轮回连线程退出，避免新旧线程同时拨号
            if(!quietMode() && !scanTriggerEnabled())startReconnectToPeer();   // 车机同时主动回连手机（免弹窗）
            if(initiator && !scanTriggerEnabled()){
                final byte[] payload=discoveryPayload;
                handler.postDelayed(()->{
                    if(!continuous || !running || generation.get()!=token)return;
                    TestSession s=activeSession;
                    if(s!=null && s.authenticated){log("无感回连成功，保持 FDEE 广播关闭。");return;}
                    if(payload!=null && advertiser==null){
                        log("无感回连 "+SILENT_RECONNECT_MS/1000+" 秒内未完成认证，启动 FDEE 广播兜底（回到可被发现态）。");
                        startFdeeAdvertising(token,payload);
                    }
                },SILENT_RECONNECT_MS);
            }
            final int waiting=token;
            // 兜底：25 秒内手机仍未重新认证，才完整重启链路（广播会短暂中断）
            handler.postDelayed(()->{
                if(!continuous || !running || generation.get()!=waiting)return;
                TestSession s=activeSession;
                if(s!=null && s.authenticated){reconnect.reset();return;}   // 手机已自动重连成功
                log("软重连 25 秒内未收到手机重新认证，执行完整重启链路（广播短暂中断）。");
                reconnect.reset();
                stopInternal(false,true,false);
                final int g=generation.get();
                handler.postDelayed(()->{ if(generation.get()==g && continuous) start(model,true,true,microphoneAllowed,true); },800);
            },25000);
        });
    }
    /** 软重连：不动广播/热点/RFCOMM 监听，只把本轮会话状态清空，使手机重连时能重新走一次认证。 */
    private void resetSessionForReconnect(int token){
        TestSession s=activeSession;
        if(s!=null){
            synchronized(s){ s.authenticated=false; s.clearKey(); s.peer=null; }
            s.controlBusy.set(false); s.mediaBusy.set(false);
        }
        authBusy.set(false);
        silentWindowConnected=false;   // 新一轮静默窗口：任何真实连接进入后重新置位
        for(java.net.Socket so:tcpClients) try{so.close();}catch(Exception ignored){}
        tcpClients.clear();
        for(HiSightClient v:videoClients) v.close(); videoClients.clear();
        for(AudioReceiver a:audioReceivers) a.close(); audioReceivers.clear();
        for(MicrophoneSender m:microphones) m.close(); microphones.clear();
        status.retrying(token);
    }
    private void autoRebind(int token, String model){
        /* 禁止自动轮换：手机侧保存的是本车机的 authId 与长期公钥，换 id/pin 或删除
         * 派生身份会让已建立的信任凭据彻底失效，之后永远无法免输码重连。
         * 免密失败时只保持身份与连接码不变、继续广播等待手机重试；重绑由用户在
         * 手机侧删除连接记录后手动完成。身份缺失时 WireIdentity.ensure() 会自愈。 */
        WireIdentity.ensure(context);
        if(generation.get()==token && running) log("免输码验证未通过：保持车机派生身份与凭据不变，等待手机重试（已禁用自动轮换）。");
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
    void stop(boolean announce) {
        boolean preserveProblem=!announce && !isActive() && status.snapshot(false).problem;
        String previousProblem=status.snapshot(false).detail;
        boolean wasContinuous=continuous;
        continuous=false;
        stopInternal(announce,false,true);
        if(preserveProblem)status.failed(generation.get(),previousProblem);else status.stopped(generation.get());
        if(wasContinuous)((LabApplication)context).video().close();
    }
    private void stopInternal(boolean announce,boolean preserveVideo,boolean notifyStopped) {
        boolean wasRunning = running;
        running = false; generation.incrementAndGet();
        stopAdvertising();
        stopPeerScan();
        if (gattProbe != null) { gattProbe.close(); gattProbe = null; }
        silentWindowConnected=false;
        synchronized(this){ reconnectEpoch++; reconnectLoopToken=-1; reconnectLoopEpoch=-1; initiatorControl=null; }
        discoveryPayload=null;
        for (BluetoothServerSocket server : servers) try { server.close(); } catch (IOException ignored) { }
        servers.clear();
        for (BluetoothSocket socket : clients) try { socket.close(); } catch (IOException ignored) { }
        clients.clear();
        for (java.net.Socket socket : tcpClients) try { socket.close(); } catch (IOException ignored) { }
        tcpClients.clear();
        for (java.net.ServerSocket server : tcpServers) try { server.close(); } catch (IOException ignored) { }
        tcpServers.clear();
        for(MicrophoneSender mic:microphones)mic.close();microphones.clear();
        for(AudioReceiver audio:audioReceivers)audio.close();audioReceivers.clear();
        for(HiSightClient video:videoClients)video.close();videoClients.clear();
        if(!preserveVideo)for(TestSession session:sessions)if(session.video){((LabApplication)context).video().close();break;}
        for(TestSession session:sessions)session.clearKey();sessions.clear();
        networkResource = null;
        if (hotspot != null) { hotspot.close(); hotspot = null; }
        if (localHotspot != null) { localHotspot.close(); localHotspot = null; }
        if (notifyStopped) {
            final int stoppedGeneration=generation.get();
            handler.post(()->{if(!running && generation.get()==stoppedGeneration)onStopped.run();});
        }
        if (announce && wasRunning) log("测试已停止，实验监听与 BLE 广播已关闭。系统蓝牙可发现状态按系统倒计时结束。");
    }
}
