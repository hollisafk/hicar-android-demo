package local.hicarlab;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Wire identity only. The persistent HUKS hardware seed must stay unchanged. */
final class WireIdentity {
    /** 00048000 = A15W 盒子的真实 HiCar 型号（2026-11-08 从手机连接日志提取，
     *  AntiFakeValidator reportVehicleHash 中 modelId 与 centralControlModel:A15W 交叉印证）。
     *  手机对该型号走 getDeviceReconnectType 免确认路径（云端白名单）。 */
    static final String DEFAULT_MODEL = "00048000";

    static String derive(String model, String address) {
        if (model == null || !model.matches("(?i)[0-9a-f]{8}")) throw new IllegalArgumentException("model");
        if (address == null) throw new IllegalArgumentException("address");
        String mac = address.trim().toUpperCase(Locale.ROOT);
        if (!mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") || mac.equals("02:00:00:00:00:00")
                || mac.equals("00:00:00:00:00:00") || (Integer.parseInt(mac.substring(0, 2), 16) & 1) != 0)
            throw new IllegalArgumentException("address");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (model.toUpperCase(Locale.ROOT) + mac.replace(":", "")).getBytes(StandardCharsets.US_ASCII));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02X", b & 255));
            java.util.Arrays.fill(digest, (byte) 0);
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String select(String seed, String model, String savedModel, String savedWireId) {
        if (model != null && model.equalsIgnoreCase(savedModel) && savedWireId != null
                && savedWireId.matches("[0-9A-F]{64}")) return savedWireId;
        return seed;
    }

    /** 仅接受真实、可用的蓝牙地址：排除 Android 6+ 的屏蔽地址与本地管理位异常的随机值。 */
    private static boolean valid(String s) {
        if (s == null) return false;
        String a = s.trim();
        if (!a.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return false;
        a = a.toUpperCase(Locale.ROOT);
        if (a.equals("02:00:00:00:00:00") || a.equals("00:00:00:00:00:00")) return false;
        // 蓝牙 MAC 第一个字节的最低位必须是 0（全局管理地址），否则为本地伪造地址。
        return (Integer.parseInt(a.substring(0, 2), 16) & 1) == 0;
    }

    /** 探测本机真实蓝牙地址（最佳努力）。普通应用在无 root 的 Android 6+ 上往往读不到，
     *  但部分 OEM ROM（如小米）仍会通过 Settings 暴露。返回 null 表示无法自动取得。 */
    private static String discoverLocalMac(Context context) {
        Set<String> candidates = new LinkedHashSet<>();
        try {
            BluetoothManager m = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = m != null ? m.getAdapter() : null;
            if (adapter != null) {
                String a = adapter.getAddress();
                if (valid(a)) candidates.add(a.toUpperCase(Locale.ROOT));
            }
        } catch (RuntimeException ignored) { }
        try {
            String a = Settings.Secure.getString(context.getContentResolver(), "bluetooth_address");
            if (valid(a)) candidates.add(a.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) { }
        try {
            String a = Settings.Global.getString(context.getContentResolver(), "bluetooth_address");
            if (valid(a)) candidates.add(a.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) { }
        try {
            File f = new File("/sys/class/bluetooth/hci0/address");
            if (f.canRead()) {
                String line = readFirstLine(f);
                if (valid(line)) candidates.add(line.trim().toUpperCase(Locale.ROOT));
            }
        } catch (RuntimeException ignored) { }
        if (candidates.size() == 1) return candidates.iterator().next();
        return null; // 多个不一致候选或全为空，都不猜测
    }

    /** 解析本机蓝牙地址：优先已持久化的 wire_mac，其次自动探测本机真实地址（保留获取方法）。
     *  返回 null 表示两者皆无，此时调用方应转入测试页让用户手动填写。 */
    static String resolveMac(Context context) {
        SharedPreferences p = context.getSharedPreferences("identity", 0);
        String mac = p.getString("wire_mac", null);
        if (valid(mac)) return mac.trim().toUpperCase(Locale.ROOT);
        return discoverLocalMac(context); // 可能为 null（Android 6+ 普通应用通常读不到）
    }

    private static String readFirstLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 派生身份必须与手机端 model+BR-MAC 反查结果一致，并在任何失败/重启后保持不变。
     *  历史上重绑逻辑会 remove("wire_id")/remove("wire_model")，导致车机悄悄退回随机
     *  身份、免输码永久失效。这里把蓝牙地址单独持久化，一旦身份丢失就自动重算回来。
     *
     *  跨设备修正：若已绑定的 wire_mac 与“本机真实蓝牙地址”不一致（说明 APK/数据被装到
     *  了另一台设备），且非用户显式指定，则自动重置为本机地址重新派生——这是“装到其他
     *  设备重连失败”的根因修复。 */
    static void ensure(Context context) {
        SharedPreferences p = context.getSharedPreferences("identity", 0);
        String id = p.getString("wire_id", null);
        String mac = p.getString("wire_mac", null);
        String model = p.getString("wire_model", DEFAULT_MODEL);
        boolean explicit = p.getBoolean("wire_mac_explicit", false);

        DiagnosticLog.add(context, "身份自检：wire_mac=" + mac + "  wire_model=" + model
                + "  explicit=" + explicit
                + "  id前8位=" + (id == null ? "null" : id.substring(0, 8)));

        if (id != null && id.matches("[0-9A-F]{64}")) {
            // 已存在有效身份：仅在“本机真实 MAC 与已绑定 MAC 不一致”且非显式指定时纠正。
            String live = discoverLocalMac(context);
            if (live != null && mac != null && !live.equalsIgnoreCase(mac) && !explicit) {
                DiagnosticLog.add(context, "检测到已绑定蓝牙地址 " + mac
                        + " 与本机真实地址 " + live + " 不一致：APK/数据可能装到了另一台设备。"
                        + "已自动重置为本机地址并重新派生身份；请在手机侧删除旧 HiCar 记录后重新配对。");
                p.edit().remove("wire_id").remove("wire_model").remove("wire_mac")
                        .remove("wire_mac_explicit").commit();
                mac = null;
            } else {
                if (live != null && mac != null && !live.equalsIgnoreCase(mac)) {
                    DiagnosticLog.add(context, "警告：显式指定的 wire_mac=" + mac
                            + " 与本机探测到的蓝牙地址 " + live + " 不一致（explicit=true，未自动纠正）。"
                            + "若免输码重连失败，请核对系统真实 BR/EDR MAC 后用 adb 重新指定。");
                }
                return;
            }
        }

        if (mac != null) {
            try {
                String normalized = model.trim().toUpperCase(Locale.ROOT);
                String derived = derive(normalized, mac);
                p.edit().putString("wire_model", normalized).putString("wire_id", derived).commit();
                return;
            } catch (IllegalArgumentException ignored) { /* 地址无效时继续往下尝试 */ }
        }

        // 没有可用的 wire_mac：尝试自动探测本机蓝牙地址并派生。
        String live = discoverLocalMac(context);
        if (live != null) {
            try {
                String derived = derive(DEFAULT_MODEL, live);
                p.edit().putString("wire_model", DEFAULT_MODEL).putString("wire_mac", live)
                        .putString("wire_id", derived).putBoolean("wire_mac_explicit", false).commit();
                DiagnosticLog.add(context, "已用本机蓝牙地址自动派生 HiCar 身份（model=" + DEFAULT_MODEL
                        + "，地址 " + live + "）；免输码重连已具备条件。如手机仍提示不匹配，请在设置页手动核对本机蓝牙地址。");
                return;
            } catch (IllegalArgumentException ignored) { }
        }

        // 彻底无法取得本机地址：保持现状，明确提示需手动配置。
        DiagnosticLog.add(context, "未能自动取得本机蓝牙地址（Android 6+ 普通应用通常读不到）。"
                + "如需免输码重连，请在设置页“设置本机蓝牙身份”填写本机系统状态里的蓝牙地址，"
                + "或执行 adb shell am start -n local.hicarlab/.MainActivity --es wire_mac AA:BB:CC:DD:EE:FF");
    }

    /** 用户（或 adb）显式指定本机蓝牙地址时调用，标记为非自动、不可被跨设备纠正覆盖。 */
    static void setExplicit(Context context, String model, String address) {
        String mac = address.trim().toUpperCase(Locale.ROOT);
        String derived = derive(model, mac);
        context.getSharedPreferences("identity", 0).edit()
                .putString("wire_model", model.trim().toUpperCase(Locale.ROOT))
                .putString("wire_mac", mac)
                .putString("wire_id", derived)
                .putBoolean("wire_mac_explicit", true)
                .commit();
    }
}
