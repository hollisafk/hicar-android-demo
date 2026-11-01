package local.hicarlab;

// Adapted from Aki894/CarLink nearby_wire.py and control.py (MIT).
import java.io.*;
import java.util.*;

public final class NearbyWire {
    static final int MAX=65536;
    static class Invalid extends IOException { Invalid(String s){super(s);} }
    static class Short extends Exception {}
    static class Cursor {
        byte[] b; int p;
        Cursor(byte[] b,int p){this.b=b;this.p=p;}
        int uint() throws Short,Invalid {
            int v=0;
            for(int s=0;s<28;s+=7){if(p==b.length)throw new Short();int x=b[p++]&255;v|=(x&127)<<s;if((x&128)!=0)return v;}
            throw new Invalid("varint");
        }
        byte[] take(int n) throws Short,Invalid {
            if(n<0||n>MAX)throw new Invalid("length");
            if(n>b.length-p)throw new Short();byte[] v=Arrays.copyOfRange(b,p,p+n);p+=n;return v;
        }
    }
    static byte[] uint(int v){
        if(v<0||v>0xfffffff)throw new IllegalArgumentException();
        ByteArrayOutputStream o=new ByteArrayOutputStream();
        while(v>=128){o.write(v&127);v>>>=7;}o.write(v|128);return o.toByteArray();
    }
    static byte[] join(byte[]... a){ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] b:a)o.write(b,0,b.length);return o.toByteArray();}
    static byte[] tlv(int tag,byte[] v){return join(uint(tag),uint(v.length),v);}
    static int crc(byte[] b,int end){int c=0;for(int i=0;i<end;i++){c^=b[i]&255;for(int j=0;j<8;j++)c=(c>>>1)^((c&1)!=0?0xa001:0);}return c;}
    static byte[] pack(int kind,byte[] payload){
        if(payload.length>MAX)throw new IllegalArgumentException();
        byte[] b=join(new byte[]{127},uint(kind),tlv(0,new byte[0]),uint(payload.length),payload);
        int c=crc(b,b.length);return join(b,new byte[]{(byte)c,(byte)(c>>>8)});
    }
    static class Frame {int kind,flags;byte[] payload;Frame(int k,int f,byte[] p){kind=k;flags=f;payload=p;}}
    static class Decoder {
        byte[] buffer=new byte[0];boolean failed;
        List<Frame> feed(byte[] chunk,int n)throws IOException {
            if(failed)throw new Invalid("decoder failed");
            List<Frame> out=new ArrayList<>();
            try {
                if(n<0||n>4096||n>chunk.length)throw new Invalid("chunk size");
                buffer=join(buffer,Arrays.copyOf(chunk,n));
                while(buffer.length>0){
                    try {
                        if(buffer[0]!=127)throw new Invalid("marker");
                        Cursor c=new Cursor(buffer,1);int k=c.uint(),f=c.uint(),sz=c.uint();
                        if(sz>256)throw new Invalid("options");c.take(sz);int len=c.uint();byte[] p=c.take(len);
                        byte[] sum=c.take(2);if(crc(buffer,c.p-2)!=((sum[0]&255)|((sum[1]&255)<<8)))throw new Invalid("CRC");
                        out.add(new Frame(k,f,p));buffer=Arrays.copyOfRange(buffer,c.p,buffer.length);
                    }catch(Short incomplete){break;}
                }
                if(buffer.length>MAX+288)throw new Invalid("buffer limit");
            }catch(IOException e){failed=true;buffer=new byte[0];throw e;}
            return out;
        }
    }
    static class Field {int tag;byte[] value;Field(int t,byte[] v){tag=t;value=v;}}
    static List<Field> fields(byte[] data)throws IOException {
        Cursor c=new Cursor(data,0);List<Field> out=new ArrayList<>();
        try {while(c.p<data.length){int t=c.uint();out.add(new Field(t,c.take(c.uint())));}}catch(Short e){throw new Invalid("TLV truncated");}
        return out;
    }
    static class Negotiator {
        byte[] summary;int seen;int channel;
        Negotiator(byte[] s,int c){summary=s;channel=c;}
        byte[] respond(Frame frame)throws IOException {
            if((frame.flags&1)!=0)throw new Invalid("encrypted before authentication");
            ByteArrayOutputStream o=new ByteArrayOutputStream();int next=seen;
            for(Field f:fields(frame.payload)){
                byte[] reply=null;
                if(frame.kind==0){
                    if(f.tag==1){if(f.value.length!=1||(f.value[0]&255)<2)throw new Invalid("CCMP version");reply=tlv(2,new byte[]{2});next|=1;}
                    if(f.tag==3){if(f.value.length<1||f.value.length>256)throw new Invalid("summary");reply=tlv(4,summary);next|=2;}
                    if(f.tag==5){if(f.value.length!=1||(f.value[0]&255)>2)throw new Invalid("framing");reply=tlv(6,new byte[]{0});next|=4;}
                }else if(frame.kind==2&&f.tag==3){
                    if(seen!=7||channel<1||channel>30)throw new Invalid("business channel unavailable");
                    Cursor c=new Cursor(f.value,0);
                    try {
                        int transport=c.uint(),business=c.uint(),size=c.uint();
                        if(size>128)throw new Invalid("business tag");c.take(size);int protocol=c.uint();
                        if(transport!=2||business!=19||protocol!=0||c.p!=f.value.length)throw new Invalid("unsupported business");
                        UUID u=UUID.fromString("0000fe36-0000-1000-8000-00805f9b34fb");
                        java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
                        reply=tlv(4,join(uint(0),uint(channel),f.value,uint(16),b.array()));
                    }catch(Short e){throw new Invalid("business truncated");}
                }
                if(reply!=null)o.write(reply,0,reply.length);
            }
            seen=next;return o.size()==0?null:pack(frame.kind,o.toByteArray());
        }
    }
}
