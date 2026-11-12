package local.hicarlab;

/**
 * Car-screen display specification shared by the whole HiSight video pipeline.
 * The phone renders and encodes the HiCar UI at these dimensions, so we report
 * the device's real landscape resolution (aligned to 16px for H.264) instead of
 * a fixed 1920x864. That makes the projection fill any screen edge-to-edge with
 * no pillar boxes, and keeps touch mapping linear (view size == content size).
 * Keep every consumer in sync (capabilities, decoder buffer, view, touch map).
 */
final class VideoSpec {
    /** Encoded / display size; configured from the real screen at startup. */
    static int WIDTH  = 1920;
    static int HEIGHT = 864;

    /** Align a dimension down to a multiple of 16 (H.264 safe, avoids overflow). */
    static int align(int v){ if(v<16) return 16; return v - (v & 15); }

    /** Derive the display size from the device's real landscape resolution. */
    static void configure(int rawW, int rawH){
        int w = rawW, h = rawH;
        if(w < h){ int t=w; w=h; h=t; }      // force landscape
        WIDTH  = align(w);
        HEIGHT = align(h);
    }

    /** Capability string reported to the phone via RTSP GET_PARAMETER. */
    static String formats(){
        return "codecs 1;fps 30;gop 30;bitrate 8000000;vbr-min 4000000;vbr-max 10000000;dpi 160;"
             + "scr-w " + WIDTH + ";scr-h " + HEIGHT + ";width " + WIDTH + ";height " + HEIGHT;
    }

    private VideoSpec(){}
}
