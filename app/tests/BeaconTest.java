package local.hicarlab;
import java.util.Arrays;
import local.hicarlab.DiscoveryBeacon;

public class BeaconTest {
    public static void main(String[] args) {
        // Fixed upstream protocol vector; this verifies wire compatibility, not discovery.
        byte[] expected = {1,1,1,3,0,17,34,4,51,16,1};
        if (!Arrays.equals(expected, DiscoveryBeacon.encode("00112233", 1))) throw new AssertionError("wire vector");
        for (String invalid : new String[]{null,"","001122","0011223344","0011zz33","00 11 22","１２３４５６７８"}) {
            try { DiscoveryBeacon.encode(invalid, 1); throw new AssertionError("accepted invalid model"); }
            catch (IllegalArgumentException expectedError) { }
        }
        for (int invalid : new int[]{-1,256}) {
            try { DiscoveryBeacon.encode("00112233", invalid); throw new AssertionError("accepted invalid sequence"); }
            catch (IllegalArgumentException expectedError) { }
        }
        for (String model : new String[]{"00112233","FFFFFFFF","a1b2c3d4","00000000"}) {
            for (int sequence : new int[]{0,1,2,255}) {
                byte[] packet = DiscoveryBeacon.encode(model, sequence);
                if (packet.length != 11 || (packet[10] & 255) != sequence) throw new AssertionError("length/sequence");
                StringBuilder hex = new StringBuilder();
                for (byte b : packet) hex.append(String.format("%02x", b & 255));
                System.out.println(model + " " + sequence + " " + hex);
            }
        }
    }
}
