package local.hicarlab;

/** Synchronous, process-global HUKS runtime; callers serialize native use. */
final class NativeAuth {
    static {System.loadLibrary("carlink_hichain");}
    static synchronized native int init(String path,String hardwareSeed);
    static synchronized native long create(String self,String peer,String pin,int role);
    static synchronized native byte[][] step(long handle,byte[] packet,int keyLength);
    static synchronized native byte[] sessionKey(long handle);
    static synchronized native void close(long handle);
    /* 可选 PSK-SPEKE / AUTH_* wire 兼容层：上游 native 库未编入这两个实验模块，
     * 保持纯 Java 空实现，避免低版本设备加载时抛出 UnsatisfiedLinkError。
     * 两个运行时开关默认关闭，核心 HiChain 会话不受影响。 */
    static synchronized void enablePskSpeke(int on){}
    static synchronized int hasPsk(String peer){return 0;}
    static synchronized void forgetPsk(String peer){}
    static synchronized void enableWireAuth(int on){}
    static synchronized int wireAuthEnabled(){return 0;}
}
