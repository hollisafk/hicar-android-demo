package local.hicarlab;
import java.util.*;
public class CipherTest {
 static byte[] hex(String x){byte[]b=new byte[x.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(x.substring(i*2,i*2+2),16);return b;}
 static void check(boolean x){if(!x)throw new AssertionError();}
 public static void main(String[]a)throws Exception{
  byte[] key=hex("2b7e151628aed2a6abf7158809cf4f3c"),iv=hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),plain=hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"),expected=hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff");
  SessionCipher c=new SessionCipher(key,1500);check(Arrays.equals(c.transform(plain,iv),expected));check(Arrays.equals(c.decrypt(NearbyWire.join(iv,expected)),plain));
  byte[] first=c.encrypt(plain),second=c.encrypt(plain);check(!Arrays.equals(Arrays.copyOf(first,16),Arrays.copyOf(second,16)));check(Arrays.equals(c.decrypt(first),plain));
  for(byte[] bad:new byte[][]{new byte[0],new byte[16],new byte[1500]}){boolean rejected=false;try{c.decrypt(bad);}catch(Exception e){rejected=true;}check(rejected);}
  c.close();boolean closed=false;try{c.encrypt(plain);}catch(Exception e){closed=true;}check(closed);
  byte[] longKey=new byte[32];System.arraycopy(key,0,longKey,0,16);try(SessionCipher d=new SessionCipher(longKey,1500)){check(Arrays.equals(d.transform(plain,iv),expected));}
  System.out.println("AES-CTR NIST vector, authenticated-key prefix, random IV, size bounds and closed-key rejection passed");
 }
}
