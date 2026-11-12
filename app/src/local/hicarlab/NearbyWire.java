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
        /* HelloCar session.rs::Negotiator 的 initiator 侧（可信自动重连/无感回连）。默认关闭，
         * 只有车机主动回连调用 start() 后才启用，原有 responder 行为不变。 */
        boolean initiator,requested;
        /** 暂时不主动向手机索取业务端口：HiCar 正常方向是手机连回车机 FE36。
         *  超时后再放开，回落到 HelloCar 的主动索取路径。 */
        boolean deferRequest;
        int endpoint=-1;
        private final List<byte[]> pending=new ArrayList<>();
        Negotiator(byte[] s,int c){summary=s;channel=c;}
        /** 车机作为 CCMP 发起方：kind 0 首帧载荷 TLV 1=[2], 3=summary, 5=[0]。 */
        byte[] start(){initiator=true;return join(tlv(1,new byte[]{2}),tlv(3,summary),tlv(5,new byte[]{0}));}
        /** 暂缓主动 business 索取，先等手机连回车机自己的 FE36。 */
        void deferOwnBusinessRequest(){deferRequest=true;}
        /** 等待窗口结束，允许按 HelloCar 方式主动索取手机的 business 端口。 */
        void allowOwnBusinessRequest(){deferRequest=false;}
        /** 放开暂缓后立即补发一次 business 请求（若有必要）。 */
        void requestOwnBusinessIfReady(){
            if(initiator&&seen==7&&!requested){pending.add(pack(2,tlv(3,join(uint(2),uint(19),uint(0),uint(0)))));requested=true;}
        }
        boolean complete(){return seen==7;}
        /** 取回并清空手机侧 business endpoint（RFCOMM 通道号），-1 表示尚未协商出来。 */
        int endpoint(){int p=endpoint;endpoint=-1;return p;}
        List<byte[]> takePending(){List<byte[]> out=new ArrayList<>(pending);pending.clear();return out;}
        byte[] respond(Frame frame)throws IOException {
            if((frame.flags&1)!=0)throw new Invalid("encrypted before authentication");
            ByteArrayOutputStream o=new ByteArrayOutputStream();int next=seen;
            for(Field f:fields(frame.payload)){
                byte[] reply=null;
                if(frame.kind==0){
                    if(f.tag==1){if(f.value.length!=1||(f.value[0]&255)<2)throw new Invalid("CCMP version");reply=tlv(2,new byte[]{2});next|=1;}
                    if(f.tag==2){if(f.value.length!=1||(f.value[0]&255)<2)throw new Invalid("CCMP version response");next|=1;}
                    if(f.tag==4){if(f.value.length<1||f.value.length>256)throw new Invalid("remote summary");next|=2;}
                    if(f.tag==6){if(f.value.length!=1||f.value[0]!=0)throw new Invalid("encrypted Nearby channel unsupported");next|=4;}
                    if(f.tag==3){if(f.value.length<1||f.value.length>256)throw new Invalid("summary");reply=tlv(4,summary);next|=2;}
                    if(f.tag==5){if(f.value.length!=1||(f.value[0]&255)>2)throw new Invalid("framing");reply=tlv(6,new byte[]{0});next|=4;}
                }else if(frame.kind==2&&f.tag==3){
                    /* 车机作为 initiator 主动拨通手机 FE35 时，本端没有等待回应的本地 business
                     * 通道（channel=-1）。手机若同时请求 business，按 HelloCar 的做法忽略它，
                     * 让车机自己发出的请求胜出；旧逻辑会抛异常并关闭整条控制链路。 */
                    if(initiator&&(channel<1||channel>30))continue;
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
                    /* HelloCar：手机同时发起 business 时让位给手机，避免两侧抢同一通道。 */
                    if(initiator){initiator=false;pending.clear();requested=false;endpoint=-1;}
                }else if(frame.kind==2&&f.tag==4&&initiator){
                    /* 我们主动请求的响应：0(result) / port / 2 / 19 / tag / 0 / 16 / FE36 UUID(16) */
                    Cursor c=new Cursor(f.value,0);
                    try {
                        int result=c.uint(),port=c.uint(),transport=c.uint(),business=c.uint(),size=c.uint();
                        if(result!=0||port<1||port>30||transport!=2||business!=19||size>128||size>f.value.length-c.p)
                            throw new Invalid("remote business rejected");
                        c.take(size);
                        if(c.uint()!=0||c.uint()!=16||f.value.length-c.p!=16)throw new Invalid("invalid business transport");
                        endpoint=port;
                    }catch(Short e){throw new Invalid("business response truncated");}
                }
                if(reply!=null)o.write(reply,0,reply.length);
            }
            seen=next;
            /* 协商完成后由车机主动请求 business 通道（uint 2/19/0/0 装入 TLV 3，kind 2）。
             * deferRequest 时先等手机来连车机自己的 FE36，避免向已配对手机索取它不提供的端口。 */
            if(initiator&&seen==7&&!requested&&!deferRequest){
                pending.add(pack(2,tlv(3,join(uint(2),uint(19),uint(0),uint(0)))));
                requested=true;
            }
            return o.size()==0?null:pack(frame.kind,o.toByteArray());
        }
    }
}
