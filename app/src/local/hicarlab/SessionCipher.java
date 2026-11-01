package local.hicarlab;

// CarLink cipher.py (MIT): authenticated HiChain key prefix, per-record AES CTR.
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.security.*;
import java.util.*;
import java.io.IOException;

final class SessionCipher implements AutoCloseable {
    private byte[] key;
    private final SecureRandom random = new SecureRandom();
    private final int limit;
    SessionCipher(byte[] sessionKey,int maxRecord) {
        if(sessionKey==null||(sessionKey.length!=16&&sessionKey.length!=32)||maxRecord<17||maxRecord>10485760)
            throw new IllegalArgumentException("authenticated key/record size");
        key=Arrays.copyOf(sessionKey,16);limit=maxRecord;
    }
    synchronized byte[] transform(byte[] data,byte[] iv)throws GeneralSecurityException,IOException {
        if(key==null||iv==null||iv.length!=16||data==null||data.length<1||data.length>=limit)throw new IOException("cipher record");
        Cipher c=Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));return c.doFinal(data);
    }
    synchronized byte[] encrypt(byte[] plain)throws GeneralSecurityException,IOException {
        if(plain==null||plain.length<1||plain.length+16>=limit)throw new IOException("cipher record size");
        byte[] iv=new byte[16];random.nextBytes(iv);return NearbyWire.join(iv,transform(plain,iv));
    }
    synchronized byte[] decrypt(byte[] record)throws GeneralSecurityException,IOException {
        if(record==null||record.length<=16||record.length>=limit)throw new IOException("encrypted record size");
        return transform(Arrays.copyOfRange(record,16,record.length),Arrays.copyOf(record,16));
    }
    @Override public synchronized void close(){if(key!=null)Arrays.fill(key,(byte)0);key=null;}
}
