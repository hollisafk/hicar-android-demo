package local.hicarlab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Wire identity only. The persistent HUKS hardware seed must stay unchanged. */
final class WireIdentity {
    static String derive(String model, String address) {
        if (model == null || !model.matches("(?i)[0-9a-f]{8}")) throw new IllegalArgumentException("model");
        if (address == null) throw new IllegalArgumentException("address");
        String mac = address.trim().toUpperCase(Locale.ROOT);
        if (!mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") || mac.equals("02:00:00:00:00:00")
                || mac.equals("00:00:00:00:00:00") || (Integer.parseInt(mac.substring(0,2),16)&1)!=0)
            throw new IllegalArgumentException("address");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (model.toUpperCase(Locale.ROOT)+mac.replace(":", "")).getBytes(StandardCharsets.US_ASCII));
            StringBuilder result = new StringBuilder();
            for (byte b: digest) result.append(String.format(Locale.ROOT,"%02X", b&255));
            java.util.Arrays.fill(digest,(byte)0);
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String select(String seed, String model, String savedModel, String savedWireId) {
        if (model != null && model.equalsIgnoreCase(savedModel) && savedWireId != null
                && savedWireId.matches("[0-9A-F]{64}")) return savedWireId;
        return seed;
    }
}
