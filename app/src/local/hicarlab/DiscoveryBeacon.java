package local.hicarlab;

/** 真实 HiCar FDEE 广播 service data（编码规则与华为 HelloCar radio.rs::beacon()
 *  以及 CarLink experiments/hicar/probe.py::beacon() 一致）。
 *
 * 格式（11 字节）：01 01 01 03 m0 m1 m2 04 m3 10 seq
 *   - 前 4 字节固定头；
 *   - 型号 8 位 hex（如 00048000）展开为 4 字节 m0 m1 m2 m3，
 *     在第 3 字节 m2 之后插入固定 tag 0x04 —— HelloCar beacon() 的原样排布
 *     （beacon("00048000") = 01 01 01 03 00 04 93 04 01 10 seq，已逐字节验证）；
 *   - 0x10 为 tag，最后 1 字节是发现序号（每轮连接递增，0..255）。
 *
 * 注意：此前本文件曾把抓包中紧跟 service data 的 `11 e7` 当成指纹尾，扩成 13 字节，
 * 并固定写死第 11 字节为 0x72，导致 sequence 参数完全没被使用。抓包里的 0x72 实际
 * 就是当时的 sequence 值。为兼容不同手机代际，encode(..., true) 仍可输出
 * `... 10 seq 11 e7` 的 13 字节变体用于真机对比，但默认使用 11 字节标准布局。
 *
 * 2026-11-08：盒子（A15W）真机身份已从手机连接日志提取 —— modelId = 00048000，
 * 手机对它走 getDeviceReconnectType 免确认路径（云端白名单型号）。
 * encode() 现按传入 model 动态编码中段，与 WireIdentity 的派生身份保持同一来源。 */
public final class DiscoveryBeacon {
    /** 无有效型号时的兜底：00048000 的编码（盒子白名单型号），序号先填 1。 */
    private static final byte[] FALLBACK = {
            0x01, 0x01, 0x01, 0x03,
            0x00, 0x03, (byte) 0xEC, 0x04, 0x08,
            0x10, 0x01
    };

    /** 按型号 8 位 hex 编码 11 字节 FDEE service data。非法/空型号返回 00048000 兜底帧。 */
    public static byte[] encode(String model, int sequence) {
        return encode(model, sequence, false);
    }

    /** legacy13=true 时在标准 11 字节后补 `11 e7`，还原此前抓包中实际生效的 13 字节广播，
     *  用于与 11 字节标准帧做真机对比（0x72 位置改由真实递增序号填充）。 */
    public static byte[] encode(String model, int sequence, boolean legacy13) {
        byte[] out = new byte[legacy13 ? 13 : 11];
        System.arraycopy(FALLBACK, 0, out, 0, 10);
        out[10] = (byte) (sequence & 255);
        if (legacy13) { out[11] = 0x11; out[12] = (byte) 0xE7; }
        if (model != null) {
            String hex = model.trim().toUpperCase(java.util.Locale.ROOT);
            if (hex.matches("[0-9A-F]{8}")) {
                for (int i = 0; i < 4; i++) {
                    int slot = (i < 3) ? 4 + i : 8;   // m0 m1 m2 落 4..6，m3 落 8（7 是固定 tag 0x04）
                    out[slot] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
            }
        }
        return out;
    }
}
