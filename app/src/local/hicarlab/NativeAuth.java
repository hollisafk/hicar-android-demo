package local.hicarlab;

/** Synchronous, process-global HUKS runtime; callers serialize native use. */
final class NativeAuth {
    static {System.loadLibrary("carlink_hichain");}
    static synchronized native int init(String path,String hardwareSeed);
    static synchronized native long create(String self,String peer,String pin,int role);
    static synchronized native byte[][] step(long handle,byte[] packet,int keyLength);
    static synchronized native byte[] sessionKey(long handle);
    static synchronized native void close(long handle);
    /* Optional PSK-SPEKE layer; inert unless the native library was built
     * with -DENABLE_PSK_SPEKE=ON. Never exposes key material to Java. */
    static synchronized native void enablePskSpeke(int on);
    static synchronized native int hasPsk(String peer);
    static synchronized native void forgetPsk(String peer);
    /* Optional Huawei AUTH_* wire compatibility; inert unless the native
     * library was built with -DENABLE_WIRE_AUTH=ON. */
    static synchronized native void enableWireAuth(int on);
    static synchronized native int wireAuthEnabled();
}
