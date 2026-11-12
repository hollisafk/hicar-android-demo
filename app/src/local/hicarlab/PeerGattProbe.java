package local.hicarlab;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** 车机作为 BLE 中心主动连手机，走手机 FE35 GATT 服务完成 CCMP（Nearby）协商。
 *
 *  真实盒子的「主动扫描并自动连接手机」就是这条链路：手机在 FE35 服务下开了一个可写特征
 *  （2a02）和一个通知特征（2a03），盒子把 start 帧写进去、从通知里读回复，
 *  协商完成后手机自己连回车机的 FE36 业务通道，全程不需要用户在手机上点确认。
 *
 *  只用于实验：所有收发都写日志，协议解析复用 NearbyWire。 */
final class PeerGattProbe {
    interface Sink { void log(String message); void endpoint(int channel); }

    private static final UUID NEARBY_SERVICE = UUID.fromString("0000fe35-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_WRITE = UUID.fromString("00002a02-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_NOTIFY = UUID.fromString("00002a03-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_NOTIFY_ALT = UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Sink sink;
    private final String summary;
    private final int channel;
    private final boolean handshake;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private NearbyWire.Negotiator negotiator;
    private NearbyWire.Decoder decoder;
    private boolean started;

    PeerGattProbe(Context context, String summary, int channel, boolean handshake, Sink sink) {
        this.context = context;
        this.summary = summary;
        this.channel = channel;
        this.handshake = handshake;
        this.sink = sink;
    }

    boolean busy() { return gatt != null; }

    void connect(final BluetoothDevice device) {
        if (gatt != null) return;
        try {
            BluetoothGattCallback callback = new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    sink.log("GATT 连接状态：status=" + status + " state=" + newState);
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { g.requestMtu(512); } catch (RuntimeException ignored) { }
                        if (!g.discoverServices()) sink.log("GATT 服务发现启动失败。");
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        close();
                    }
                }

                @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                    sink.log("GATT MTU：" + mtu + " status=" + status);
                }

                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    if (g.getServices() == null) { sink.log("GATT 服务发现完成但列表为空。"); return; }
                    sink.log("GATT 服务发现完成：status=" + status + "，服务数=" + g.getServices().size());
                    for (BluetoothGattService service : g.getServices()) {
                        sink.log("GATT 服务 " + service.getUuid());
                        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                            sink.log("  特征 " + characteristic.getUuid()
                                    + " 属性=0x" + Integer.toHexString(characteristic.getProperties()));
                            if (characteristic.getUuid().equals(CHAR_NAME)
                                    && (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                                g.readCharacteristic(characteristic);
                            }
                        }
                    }
                    BluetoothGattService nearby = g.getService(NEARBY_SERVICE);
                    if (nearby == null) { sink.log("手机没有暴露 FE35 GATT 服务，BLE 触发不可用。"); return; }
                    writeChar = nearby.getCharacteristic(CHAR_WRITE);
                    notifyChar = nearby.getCharacteristic(CHAR_NOTIFY);
                    if (notifyChar == null) notifyChar = nearby.getCharacteristic(CHAR_NOTIFY_ALT);
                    sink.log("FE35 GATT：可写特征=" + (writeChar == null ? "无" : writeChar.getUuid())
                            + "，通知特征=" + (notifyChar == null ? "无" : notifyChar.getUuid()));
                    if (writeChar == null || notifyChar == null) return;
                    if (!handshake) { sink.log("只做枚举，不发送 CCMP 帧（gatt_ccmp 未开启）。"); return; }
                    enableNotify(g, notifyChar);
                }

                @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
                    sink.log("GATT 通知订阅：status=" + status);
                    if (status == 0) startHandshake(g);
                }

                @SuppressWarnings("deprecation")
                @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    sink.log("GATT 读取 " + c.getUuid() + " status=" + status + " 值=" + hex(c.getValue()));
                }

                @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    sink.log("GATT 写入 " + c.getUuid() + " status=" + status);
                }

                @SuppressWarnings("deprecation")
                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    onNotify(c.getValue());
                }
            };
            /* API 23 才提供带 TRANSPORT_LE 的四参重载；Android 5.1(API 22) 走三参版本。
             * 该 GATT 实验默认关闭，守卫可保证低版本加载本类时也不会解析高版本符号。 */
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, callback);
            }
            sink.log("GATT 探测：已向手机 " + device.getAddress() + " 发起 BLE 中心连接。");
        } catch (RuntimeException e) {
            sink.log("GATT 探测启动失败：" + e.getClass().getSimpleName());
            gatt = null;
        }
    }

    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
        try {
            g.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor == null) { sink.log("通知特征没有 CCCD 描述符，直接发送。"); startHandshake(g); return; }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(descriptor)) { sink.log("CCCD 写入发起失败，直接发送。"); startHandshake(g); }
        } catch (RuntimeException e) {
            sink.log("通知订阅失败：" + e.getClass().getSimpleName());
            startHandshake(g);
        }
    }

    /** 发送 CCMP start 帧：与 RFCOMM 控制通道使用同一套 NearbyWire 协商，摘要用本车机派生身份。 */
    private void startHandshake(BluetoothGatt g) {
        if (started) return;
        started = true;
        negotiator = new NearbyWire.Negotiator(summary.getBytes(StandardCharsets.US_ASCII), channel);
        negotiator.deferOwnBusinessRequest();
        decoder = new NearbyWire.Decoder();
        byte[] frame = NearbyWire.pack(0, negotiator.start());
        sink.log("GATT CCMP：发送 start 帧（" + frame.length + " 字节），摘要长度=" + summary.length());
        write(g, frame);
    }

    private void onNotify(byte[] value) {
        sink.log("GATT 通知 " + hex(value));
        if (decoder == null || negotiator == null) return;
        try {
            for (NearbyWire.Frame frame : decoder.feed(value, value.length)) {
                sink.log("GATT CCMP 收到帧：kind=" + frame.kind + " flags=" + frame.flags + " 载荷=" + frame.payload.length + " 字节 " + hex(frame.payload));
                byte[] reply = negotiator.respond(frame);
                if (reply != null) write(gatt, reply);
                for (byte[] pending : negotiator.takePending()) write(gatt, pending);
                if (negotiator.complete()) {
                    int endpoint = negotiator.endpoint();
                    sink.log("GATT CCMP：协商完成，手机返回业务通道 endpoint=" + endpoint);
                    if (endpoint > 0) sink.endpoint(endpoint);
                }
            }
        } catch (IOException | RuntimeException e) {
            sink.log("GATT CCMP 解析失败：" + e.getClass().getSimpleName());
        }
    }

    private void write(BluetoothGatt g, byte[] frame) {
        if (g == null || writeChar == null) return;
        try {
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            writeChar.setValue(frame);
            if (!g.writeCharacteristic(writeChar)) sink.log("GATT 写入发起失败（" + frame.length + " 字节）。");
        } catch (RuntimeException e) {
            sink.log("GATT 写入异常：" + e.getClass().getSimpleName());
        }
    }

    void close() {
        BluetoothGatt current;
        synchronized (this) { current = gatt; gatt = null; }
        if (current != null) {
            try { current.disconnect(); } catch (RuntimeException ignored) { }
            try { current.close(); } catch (RuntimeException ignored) { }
        }
    }

    private static String hex(byte[] data) {
        if (data == null) return "-";
        StringBuilder text = new StringBuilder();
        for (byte value : data) {
            if (text.length() > 0) text.append(' ');
            text.append(String.format(Locale.ROOT, "%02X", value & 255));
        }
        return text.toString();
    }
}
