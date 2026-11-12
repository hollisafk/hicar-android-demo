package local.hicarlab;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 系统 profile 主动回连实验（默认关闭，仅由 ui.profile_reconnect / adb extra 打开）。
 *
 *  目标：让手机侧产生 ACL_CONNECTED + 系统 profile 连接事件，从而走 reconnectType 2，
 *  而不是 BLE FDEE 发现态。若成立，理论上可同时做到零弹窗与免输码。
 *
 *  两段尝试：
 *  ① 反射 BluetoothAdapter.getProfileProxy 拿 A2DP / HEADSET proxy，
 *     再反射隐藏方法 connect(BluetoothDevice)。这是唯一能让手机侧系统 profile
 *     状态机真正变为 connected 的路径；普通应用通常被 BLUETOOTH_PRIVILEGED 拦下，
 *     或本机根本没注册该 profile —— 两种情况都会如实记日志。
 *  ② 拨标准 profile 的 RFCOMM UUID 并 **保持链路不关闭**（原敲门逻辑 5~8 秒就 close，
 *     很可能把刚被唤醒的手机栈又掐断）。哪怕不能驱动系统 profile，也能维持 ACL。
 *
 *  全部失败只记日志，不改变原有敲门 / 广播兜底路径。 */
final class ProfileReconnect {
    interface Events { void log(String text); }

    private static final String SUFFIX = "-0000-1000-8000-00805f9b34fb";
    private static final String[][] PROFILE_UUIDS = {
            {"HFP-AG(手机侧)", "0000111f"},
            {"HFP-HF",        "0000111e"},
            {"A2DP-Source(手机侧)", "0000110a"},
            {"A2DP-Sink",     "0000110b"},
            {"PBAP-PSE",      "0000112f"},
            {"MAP-MAS",       "00001132"},
            {"SPP",           "00001101"},
            {"HID",           "00001124"},
    };

    /** 返回需要保持不关的 ACL 链路；调用方负责在实验结束 / 停止接收时关闭。 */
    static List<BluetoothSocket> attempt(Context ctx, BluetoothDevice device, Events events) {
        List<BluetoothSocket> keep = new ArrayList<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { events.log("系统 profile 实验：蓝牙适配器不可用，跳过。"); return keep; }

        // ---------- 阶段 1：系统 profile 的隐藏 connect() ----------
        for (int profile : new int[]{2, 1}) {   // BluetoothProfile.A2DP=2, HEADSET=1
            String name = profile == 2 ? "A2DP" : "HEADSET";
            Object proxy = getProxy(ctx, adapter, profile, name, events);
            if (proxy == null) continue;
            try {
                // AOSP 的 connect() 常因该设备 PRIORITY_OFF 直接返回 false：先记录并尝试抬高优先级。
                try {
                    Method getPrio = proxy.getClass().getMethod("getPriority", BluetoothDevice.class);
                    events.log("系统 profile " + name + "：当前 priority = " + getPrio.invoke(proxy, device)
                            + "（-1=未定义 / 0=OFF / 100=ON / 1000=自动连接）。");
                } catch (Throwable ignored) {}
                try {
                    Method setPrio = proxy.getClass().getMethod("setPriority", BluetoothDevice.class, int.class);
                    events.log("系统 profile " + name + "：setPriority(1000) 返回 "
                            + setPrio.invoke(proxy, device, 1000) + "。");
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    events.log("系统 profile " + name + "：setPriority 被拒绝 —— "
                            + (cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName())
                            + "（" + (cause == null ? e.getMessage() : cause.getMessage()) + "）。");
                } catch (Throwable e) {
                    events.log("系统 profile " + name + "：setPriority 不可用 —— "
                            + e.getClass().getSimpleName() + "（" + e.getMessage() + "）。");
                }
                Method connect = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                Object result = connect.invoke(proxy, device);
                events.log("系统 profile " + name + "：隐藏 connect() 返回 " + result
                        + "（false 常见原因：本机未注册该 profile，或缺少 BLUETOOTH_PRIVILEGED）。");
                try {
                    Method state = proxy.getClass().getMethod("getConnectionState", BluetoothDevice.class);
                    events.log("系统 profile " + name + "：连接状态 = " + state.invoke(proxy, device)
                            + "（2=已连接）。");
                } catch (Throwable ignored) {}
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                events.log("系统 profile " + name + "：connect() 被拒绝 —— "
                        + (cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName())
                        + "（" + (cause == null ? e.getMessage() : cause.getMessage()) + "）。");
            } catch (Throwable e) {
                events.log("系统 profile " + name + "：connect() 不可用 —— "
                        + e.getClass().getSimpleName() + "（" + e.getMessage() + "）。");
            } finally {
                try {
                    Method cp = BluetoothAdapter.class.getMethod("closeProfileProxy", int.class,
                            Class.forName("android.bluetooth.BluetoothProfile"));
                    cp.invoke(adapter, profile, proxy);
                } catch (Throwable ignored) {}
            }
        }

        // ---------- 阶段 2：标准 profile RFCOMM，建立后保持 ----------
        for (String[] p : PROFILE_UUIDS) {
            BluetoothSocket socket = null;
            try {
                UUID uuid = UUID.fromString(p[1] + SUFFIX);
                socket = device.createInsecureRfcommSocketToServiceRecord(uuid);
                socket.connect();
                keep.add(socket);
                events.log("系统 profile RFCOMM " + p[0] + "（" + p[1]
                        + "）：ACL 已建立，保持链路不关闭；观察手机是否经 reconnectType 2 主动连回 FE35。");
                watchIncoming(socket, p[0], events);
            } catch (Throwable e) {
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                events.log("系统 profile RFCOMM " + p[0] + "（" + p[1] + "）：无法建立 —— "
                        + e.getClass().getSimpleName() + "（" + e.getMessage() + "）。");
            }
        }
        events.log("系统 profile 实验拨号完毕：保持 " + keep.size() + " 条 ACL 链路。"
                + "判据 = 手机在 FDEE 兜底广播之前主动连回本端 FE35。");
        return keep;
    }

    /** getProfileProxy(...) 需要 hidden 内部接口 ServiceListener，用动态代理绕开编译期依赖。 */
    private static Object getProxy(Context ctx, BluetoothAdapter adapter, int profile,
                                    String name, Events events) {
        try {
            Class<?> listenerClass = Class.forName("android.bluetooth.BluetoothProfile$ServiceListener");
            final Object[] holder = new Object[1];
            final CountDownLatch latch = new CountDownLatch(1);
            InvocationHandler handler = (p, method, args) -> {
                String n = method.getName();
                if ("onServiceConnected".equals(n)) { holder[0] = args[1]; latch.countDown(); return null; }
                if ("onServiceDisconnected".equals(n)) { latch.countDown(); return null; }
                if ("toString".equals(n)) return "ProfileReconnect.ServiceListener";
                if ("hashCode".equals(n)) return System.identityHashCode(holder);
                if ("equals".equals(n)) return p == args[0];
                return null;
            };
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, handler);
            Method gp = BluetoothAdapter.class.getMethod("getProfileProxy",
                    Context.class, listenerClass, int.class);
            Object ok = gp.invoke(adapter, ctx, listener, profile);
            events.log("系统 profile " + name + "：getProfileProxy 返回 " + ok + "，等待服务绑定…");
            if (!Boolean.TRUE.equals(ok)) return null;
            if (!latch.await(4, TimeUnit.SECONDS)) {
                events.log("系统 profile " + name + "：服务绑定超时（本机可能未注册该 profile）。");
                return null;
            }
            if (holder[0] == null) {
                events.log("系统 profile " + name + "：服务绑定失败或立即断开。");
                return null;
            }
            events.log("系统 profile " + name + "：proxy 已取得（"
                    + holder[0].getClass().getName() + "）。");
            return holder[0];
        } catch (Throwable e) {
            events.log("系统 profile " + name + "：getProfileProxy 异常 —— "
                    + e.getClass().getSimpleName() + "（" + e.getMessage() + "）。");
            return null;
        }
    }

    /** 只记录链路上是否有对端数据，不记录内容；线程随 socket 关闭自行结束。 */
    private static void watchIncoming(final BluetoothSocket socket, final String name, final Events events) {
        Thread t = new Thread(() -> {
            try {
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[256];
                int n = in.read(buffer);
                if (n > 0) events.log("系统 profile RFCOMM " + name + "：收到对端 " + n + " 字节（仅记长度）。");
                else events.log("系统 profile RFCOMM " + name + "：链路被对端关闭。");
            } catch (Throwable e) {
                events.log("系统 profile RFCOMM " + name + "：链路结束（"
                        + e.getClass().getSimpleName() + "）。");
            }
        }, "lab-profile-watch-" + name);
        t.setDaemon(true);
        t.start();
    }

    private ProfileReconnect() {}
}
