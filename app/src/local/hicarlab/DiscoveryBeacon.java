package local.hicarlab;

/** FDEE service data layout adapted from CarLink probe.py (MIT).
 * Copyright (c) 2026 CarLink contributors. See THIRD_PARTY_NOTICES.txt.
 * A syntactically valid model is not proof of a recognized/certified product.
 */
public final class DiscoveryBeacon {
    public static byte[] encode(String model, int sequence) {
        if (model == null || !model.matches("[0-9a-fA-F]{8}"))
            throw new IllegalArgumentException("model must be eight hexadecimal characters");
        if (sequence < 0 || sequence > 255)
            throw new IllegalArgumentException("sequence must be 0..255");
        byte[] raw = new byte[4];
        for (int i = 0; i < 4; i++) raw[i] = (byte)Integer.parseInt(model.substring(i * 2, i * 2 + 2), 16);
        return new byte[]{1, 1, 1, 3, raw[0], raw[1], raw[2], 4, raw[3], 16, (byte)sequence};
    }
}
