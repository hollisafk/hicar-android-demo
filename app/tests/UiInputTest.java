package local.hicarlab;
import java.util.*;
import java.io.IOException;

public final class UiInputTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static byte[] decode(byte[] record,SessionCipher peer)throws Exception{
        check(record[0]==0&&record[1]==0&&(((record[2]&255)<<8)|(record[3]&255))==record.length);
        return peer.decrypt(Arrays.copyOfRange(record,4,record.length));
    }
    static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte n:bytes)b.append(String.format(Locale.ROOT,"%02x",n&255));return b.toString();}
    public static void main(String[] args)throws Exception{
        byte[] key=new byte[32];for(int i=0;i<32;i++)key[i]=(byte)i;
        try(UiInput sender=new UiInput(key);SessionCipher peer=new SessionCipher(key,1500)){
            byte[] one=sender.encode(UiInput.Event.touch(0,799,479),0x01020304L),two=sender.encode(UiInput.Event.touch(0,799,479),0x01020304L);
            check(hex(decode(one,peer)).equals("ffffffffffff00000b0100031f01df0102030400"));check(!Arrays.equals(one,two));
            check(hex(decode(sender.encode(UiInput.Event.key(4,true),0),peer)).equals("ffffffffffff030009000004000000000000"));
            for(UiInput.Event bad:new UiInput.Event[]{UiInput.Event.touch(0,800,0),UiInput.Event.touch(0,0,480),UiInput.Event.touch(0,-1,0),UiInput.Event.key(85,true)}){
                boolean rejected=false;try{sender.encode(bad,0);}catch(IOException e){rejected=true;}check(rejected);
            }
        }
        check(Arrays.equals(InputRouter.map(1199,719,1200,720,false),new int[]{799,479}));
        check(InputRouter.map(1200,720,1200,720,false)==null);
        check(Arrays.equals(InputRouter.map(2000,-20,1200,720,true),new int[]{799,0}));
        check(InputRouter.map(Float.NaN,0,1200,720,true)==null&&InputRouter.map(0,0,0,720,true)==null);
        InputRouter router=new InputRouter();List<UiInput.Event> first=new ArrayList<>(),second=new ArrayList<>();InputRouter.Sender a=e->{first.add(e);return true;},b=e->{second.add(e);return true;};
        check(!router.touch(0,1,1));router.attach(a);check(!router.touch(1,1,1));check(router.touch(0,1,1)&&router.touch(2,2,3));router.cancel();router.cancel();
        check(first.size()==3&&first.get(2).action==1&&first.get(2).x==2&&first.get(2).y==3);
        router.attach(b);router.detach(a);check(router.ready());check(!router.touch(2,2,3));check(router.key(4));check(second.size()==2&&second.get(0).action==3&&second.get(1).action==4);
        router.detach(b);check(!router.ready()&&!router.key(3));
        System.out.println("PASS: Python-compatible UIBC plaintext, fresh IVs, bounds/scaling/clamping, down/move/up/cancel, key pairing and stale-session isolation");
    }
}
