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
    private volatile boolean continuous;
    private P2pHotspot hotspot;
    private LocalHotspot localHotspot;
    private volatile NetworkResource networkResource;
    private final Object networkLock = new Object();
    private final CopyOnWriteArrayList<java.net.ServerSocket> tcpServers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<java.net.Socket> tcpClients = new CopyOnWriteArrayList<>();
    private boolean nativeReady;
    private String localId,localPin,pendingModel;
    private int sequence;
    private Runnable onStopped = () -> {};
    private static final UUID FDEE = UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb");
    ReceiverEngine(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        initIdentity();
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
    private static final class TestSession {
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
            if (peer == null) peer = address;
            return peer.equals(address);
        }
    }
    private void initIdentity() {
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
        } catch (Exception | LinkageError e) { nativeReady = false; }
    }
    void start(String model, boolean withNetwork) {start(model,withNetwork,false);}
    void start(String model,boolean withNetwork,boolean withVideo){start(model,withNetwork,withVideo,false);}
    void start(String model,boolean withNetwork,boolean withVideo,boolean withMicrophone){
        if (running) { log("认证测试仍在运行，不重复启动。"); return; }
        if(android.os.Build.VERSION.SDK_INT>=31 && (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)){fail("蓝牙权限不足，请在系统设置中允许附近设备权限。");return;}
        if(!nativeReady){fail("连接组件未就绪，请重新打开应用或查看诊断页。");return;}
        if(adapter==null){fail("这台设备没有可用的蓝牙适配器。");return;}
        try{if(!adapter.isEnabled()){fail("蓝牙已关闭，请打开蓝牙后重新连接。");return;}}catch(SecurityException e){fail("蓝牙权限不足，请在系统设置中允许附近设备权限。");return;}
        pendingModel = model;
        SharedPreferences identity = context.getSharedPreferences("identity", 0);
        localId = WireIdentity.select(identity.getString("id", null), model,
                identity.getString("wire_model", ""), identity.getString("wire_id", ""));
        log(localId.equals(identity.getString("id", ""))
                ? "本轮使用原实验身份；免输码重连尚未修正。"
                : "本轮使用本机蓝牙地址派生身份；原 HUKS 种子与连接码保持；重连待实测。");
        stopInternal(false,false,false); running = true;
        continuous = withVideo;microphoneAllowed=withVideo&&withMicrophone;
        reconnect.reset();
        sequence = context.getSharedPreferences("identity", 0).getInt("discovery_sequence", 0) & 255;
        context.getSharedPreferences("identity", 0).edit().putInt("discovery_sequence", (sequence + 1) & 255).apply();
        int token = generation.incrementAndGet();
        status.begin(token);
        final byte[] payload = DiscoveryBeacon.encode(pendingModel, sequence);
        log("FDEE 实验开始：型号 " + pendingModel + "，序号 " + sequence + "；未验证手机识别。");
        sequence = (sequence + 1) & 255;
        if (withNetwork) {
            P2pHotspot.Events networkEvents = new P2pHotspot.Events() {
                public void log(String message) { ReceiverEngine.this.log(message); }
                public void failed(String reason) { if (generation.get() == token) { ReceiverEngine.this.log(reason); fail(token,(reason.contains("BSSID")||reason.contains("可核验的唯一地址"))?"热点已创建，但系统隐藏了本轮热点地址；当前连接方式需要兼容性适配。":"无法准备无线连接，请检查 Wi-Fi 权限和系统设置后重试。"); } }
                public void ready(NetworkResource.Offer offer) {
                    if (generation.get() != token) return;
                    networkResource = new NetworkResource(offer);
                    startProtocol(token, pendingModel, withVideo, () -> startFdeeAdvertising(token, payload));
                }
            };
            if (Build.VERSION.SDK_INT >= 33) { localHotspot = new LocalHotspot(context, networkEvents); localHotspot.start(); }
            else { hotspot = new P2pHotspot(context, networkEvents); hotspot.start(); }
        } else startProtocol(token, pendingModel, withVideo, () -> startFdeeAdvertising(token, payload));
        if (!continuous) handler.postDelayed(() -> { if (generation.get() == token) { log("FDEE 实验时间结束。"); stop(true); } }, 120000);
        else log("持续接收已启动：不再120秒自动停止；断线后重新等待连接，可随时手动停止。");
    }
    private void startFdeeAdvertising(int token, byte[] payload) {
        if (generation.get() != token) return;
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { log("BLE 广播器不可用，FDEE 无法发送。"); fail(token,"蓝牙广播不可用，请检查蓝牙状态后重试。"); return; }
            final BluetoothLeAdvertiser thisAdvertiser = advertiser;
            advCallback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings settings) {
                    if (generation.get() == token) {log("FDEE 广播启动成功：11 字节 service data；这不是 HiCar 连接成功。");status.advance(token,ReceiverStatus.Stage.WAITING);}
                    else try { thisAdvertiser.stopAdvertising(this); } catch (RuntimeException ignored) { }
                }
                @Override public void onStartFailure(int error) {
                    if (generation.get() == token) { log("FDEE 广播失败：" + advertiseError(error)); fail(token,"蓝牙发现广播启动失败，请稍后重新连接。"); }
                }
            };
            advertiser.startAdvertising(new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true).setTimeout(0).build(),
                    new AdvertiseData.Builder().addServiceData(new ParcelUuid(FDEE), payload).build(), advCallback);
        } catch (RuntimeException e) { log("FDEE 广播异常：" + e.getClass().getSimpleName()); fail(token,"蓝牙发现启动失败，请检查附近设备权限。"); }
    }
    private void startProtocol(int token, String model,boolean withVideo, Runnable onReady) {
        new Thread(() -> {
            try {
                BluetoothServerSocket business = adapter.listenUsingInsecureRfcommWithServiceRecord("HiCarLab Business", UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb"));
                servers.add(business);
                int resolved = -1;
                try {
                    Object value = business.getClass().getMethod("getChannel").invoke(business);
                    if (value instanceof Integer) resolved = RfcommPort.valid((Integer)value);
                    if (resolved > 0) log("RFCOMM 端口获取：getChannel。");
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
                if (resolved < 1) {
                    try {
                        java.lang.reflect.Field transport = business.getClass().getDeclaredField("mSocket");
                        transport.setAccessible(true);
                        Object socket = transport.get(business);
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
                        if (resolved > 0) log("RFCOMM 端口获取：已绑定的底层 socket；外层缓存未更新。");
                    } catch (ReflectiveOperationException | RuntimeException e) { log("底层端口读取未获支持：" + e.getClass().getSimpleName()); }
                }
                if (resolved < 1) {
                    resolved = RfcommPort.fromDescription(business.toString());
                    if (resolved > 0) log("RFCOMM 端口获取：系统 socket 描述中的 Channel 字段（待真实连接验证）。");
                }
                if (resolved < 1) { log("系统未提供可核验的 RFCOMM 端口；尚未启动广播，未猜测或固定端口。"); fail(token,"这台设备暂未提供所需蓝牙服务，请查看诊断页。"); return; }
                final int channel = resolved;
                BluetoothServerSocket control = adapter.listenUsingInsecureRfcommWithServiceRecord("NearbyServerSocket", UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb"));
                servers.add(control);
                if (generation.get() != token) { business.close(); control.close(); return; }
                log("FE35 / FE36 已注册；动态业务端口 " + channel + "，等待手机连接。");
                final TestSession session = new TestSession(model,withVideo); sessions.add(session);
                session.network = networkResource;
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
                if (clients.size() >= 6) { socket.close(); continue; }
                clients.add(socket);
                if (generation.get() != token) { socket.close(); clients.remove(socket); break; }
                if (!session.select(socket)) { log("拒绝其他设备加入当前实验会话；未记录地址。"); socket.close(); clients.remove(socket); continue; }
                if (!control && !authBusy.compareAndSet(false, true)) { log("已有认证会话正在处理或清理，暂未接收新的业务连接。"); socket.close(); clients.remove(socket); continue; }
                if(!control)status.advance(token,ReceiverStatus.Stage.VERIFYING);
                log((control ? "FE35" : "FE36") + " 收到连接；对端尚未认证。");
                new Thread(() -> protocolReceive(socket, token, model, channel, control, session), "lab-protocol-rx").start();
            }
        } catch (IOException | RuntimeException e) { if (generation.get() == token) { log("协议监听结束：" + e.getClass().getSimpleName()); requestReconnect(token,model); } }
    }
    private void protocolReceive(BluetoothSocket socket, int token, String model, int channel, boolean control, TestSession session) {
        AuthBusiness auth = null;
        try {
            byte[] buffer = new byte[4096]; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            NearbyWire.Decoder decoder = new NearbyWire.Decoder();
            NearbyWire.Negotiator negotiation = new NearbyWire.Negotiator(localId.getBytes(StandardCharsets.US_ASCII), channel);
            if (!control) auth = new AuthBusiness(localId, localPin, model, new AuthBusiness.Events() {
                @Override public void log(String message) { if (generation.get() == token) ReceiverEngine.this.log(message); }
                @Override public void authenticated(byte[] key) { synchronized(session){if (generation.get() == token) { session.setKey(key); session.authenticated = true; status.advance(token,ReceiverStatus.Stage.NETWORK); if (session.network != null) session.network.authenticated = true; }} }
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
            if(!control && !session.authenticated && generation.get()==token)status.issue(token,"连接验证未完成，请在手机重试，或点重新连接。");
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
    private void requestReconnect(int token,String model){
        if(!continuous || generation.get()!=token || !reconnect.request(token))return;
        handler.post(()->{
            if(!continuous || !running || generation.get()!=token)return;
            log("连接已断开：释放本轮网络和认证会话，保留视频窗口，重新等待手机连接。");
            stopInternal(false,true,false);
            if(!((LabApplication)context).video().restart()){fail("画面解码器未能恢复，请重新连接或重新打开应用。");return;}
            final int waiting=generation.get();
            status.retrying(waiting);
            handler.postDelayed(()->{
                if(generation.get()==waiting && continuous && ((LabApplication)context).video().ready())start(model,true,true,microphoneAllowed);
            },1500);
        });
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
        if (advertiser != null && advCallback != null) try { advertiser.stopAdvertising(advCallback); } catch (RuntimeException ignored) { }
        advertiser = null; advCallback = null;
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
