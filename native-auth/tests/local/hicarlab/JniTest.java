package local.hicarlab;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
public class JniTest {
 static int status(byte[][] out){if(out==null)throw new AssertionError("null step");IntBuffer b=ByteBuffer.wrap(out[0]).order(ByteOrder.nativeOrder()).asIntBuffer();if(b.get(3)!=0)throw new AssertionError("callback error");return b.get(1);}
 static byte[] normalize(byte[] b){return new String(b,StandardCharsets.UTF_8).replaceAll("\"(peerUserType|keyLength)\":\"(0|1|16|32)\"","\"$1\":$2").getBytes(StandardCharsets.UTF_8);}
 static void test(boolean wrong){
  long l=NativeAuth.create("11".repeat(32),"22".repeat(32),"012345",1),r=NativeAuth.create("22".repeat(32),"11".repeat(32),wrong?"987654":"012345",2);
  if(l==0||r==0)throw new AssertionError("create");
  try{
   byte[][] packets=NativeAuth.step(l,null,32);int count=0,last=-1;long target=r;
   while(packets.length>1&&count++<20){if(packets.length!=2)throw new AssertionError("packet count");packets=NativeAuth.step(target,normalize(packets[1]),32);last=status(packets);if(last==1)break;target=target==r?l:r;}
   if(wrong?last!=1:last!=0)throw new AssertionError("result "+last);
   System.out.println("JNI "+(wrong?"wrong PIN rejected":"correct PIN accepted")+" messages="+count);
  }finally{NativeAuth.close(l);NativeAuth.close(r);}
 }
 public static void main(String[]a){if(NativeAuth.init(a[0],"host-jni-test")!=0)throw new AssertionError("init");test(true);test(false);}
}
