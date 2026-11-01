package local.hicarlab;

/** Synchronous, process-global HUKS runtime; callers serialize native use. */
final class NativeAuth {
    static {System.loadLibrary("carlink_hichain");}
    static synchronized native int init(String path,String hardwareSeed);
    static synchronized native long create(String self,String peer,String pin,int role);
    static synchronized native byte[][] step(long handle,byte[] packet,int keyLength);
    static synchronized native byte[] sessionKey(long handle);
    static synchronized native void close(long handle);
}
