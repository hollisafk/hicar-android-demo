package local.hicarlab;

// Adapted from CarLink control_wire.py (MIT). No media services are announced.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;

final class ControlSession implements AutoCloseable {
    interface Events {void log(String text);default void clock(long high,long low){}}
    interface Business {List<byte[]> receive(long module,long type,byte[] body)throws IOException;}
    interface TextHandler {List<byte[]> receive(String start,Map<String,String> headers,byte[] body)throws IOException;}
    private final TextHandler textHandler;
    private final Business business;
    private final SessionCipher cipher; private final Events events;
    private byte[] wire=new byte[0],text=new byte[0];
    private boolean negotiated,failed;
    private int sequence=1;
    ControlSession(byte[] key,Events e){this(key,e,null);}
    ControlSession(byte[] key,Events e,Business b){this(key,e,b,null);}
    ControlSession(byte[] key,Events e,Business b,TextHandler handler){cipher=new SessionCipher(key,1498);events=e;business=b;textHandler=handler;}
    boolean negotiated(){return negotiated;}
    static byte[] pack(byte[] payload,int marker)throws IOException{
        if((marker!=0&&marker!=171)||payload.length<1||payload.length>=1498)throw new IOException("control record");
        return NearbyWire.join(new byte[]{(byte)marker,(byte)(payload.length>>>8),(byte)payload.length},payload);
    }
    static byte[] encode(String start,String... headers){
        StringBuilder b=new StringBuilder(start).append("\r\n");
        for(int i=0;i<headers.length;i+=2)b.append(headers[i]).append(": ").append(headers[i+1]).append("\r\n");
        return b.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
    }
    byte[] initial()throws IOException{return pack(encode("OPTIONS * DMSDP/1.0","CryptoVer","1","CryptoAbility","222","CryptoAlg","aes128-ctr","UdpKA","0","LowDelay","0","CSeq","1"),0);}
    byte[] heartbeat()throws IOException,GeneralSecurityException{
        if(!negotiated||failed)throw new IOException("control state");
        return pack(cipher.encrypt(encode("GET_PARAMETER * DMSDP/1.0","CSeq",Integer.toString(++sequence))),171);
    }
    List<byte[]> feed(byte[] input,int n)throws IOException,GeneralSecurityException{
        if(failed||n<0||n>input.length||wire.length+n>65536)throw new IOException("control input limit");
        List<byte[]> replies=new ArrayList<>();
        try{
            wire=NearbyWire.join(wire,Arrays.copyOf(input,n));
            while(wire.length>=3){
                int marker=wire[0]&255,size=((wire[1]&255)<<8)|(wire[2]&255);
                if((marker!=0&&marker!=171)||size<1||size>=1498)throw new IOException("control header");
                if(wire.length<size+3)break;
                byte[] payload=Arrays.copyOfRange(wire,3,size+3);wire=Arrays.copyOfRange(wire,size+3,wire.length);
                if(marker==171){if(!negotiated)throw new IOException("early encryption");payload=cipher.decrypt(payload);}
                else if(negotiated)throw new IOException("plaintext after encryption");
                if(payload[0]=='$'){
                    if(!negotiated||text.length!=0||payload.length<11)throw new IOException("binary order");
                    int length=((payload[9]&255)<<8)|(payload[10]&255);
                    if(length!=payload.length-11)throw new IOException("binary size");
                    long module=0,type=0;for(int i=1;i<5;i++)module=(module<<8)|(payload[i]&255);for(int i=5;i<9;i++)type=(type<<8)|(payload[i]&255);
                    events.log("DMSDP 加密业务请求：模块 "+module+"，类型 "+type+"，载荷 "+length+" 字节。");
                    if(business!=null){byte[] body=Arrays.copyOfRange(payload,11,payload.length);try{for(byte[] reply:business.receive(module,type,body))replies.add(pack(cipher.encrypt(reply),171));}finally{Arrays.fill(body,(byte)0);}}
                    Arrays.fill(payload,(byte)0);continue;
                }
                text=NearbyWire.join(text,payload);Arrays.fill(payload,(byte)0);
                if(text.length>73732)throw new IOException("message limit");
                parse(replies);
            }
            return replies;
        }catch(IOException|GeneralSecurityException|RuntimeException e){
            if(e instanceof IOException && Arrays.asList("control header","early encryption","plaintext after encryption","binary order","binary size","message limit","header limit","non-ascii header","start line","header","duplicate header","body length","body limit","crypto negotiation","clock sequence","clock value","clock port","speaker before encryption","speaker control fields","speaker trigger state","speaker simple mode","speaker setup refused","speaker transport","speaker remote port","speaker session mismatch","speaker audio format","speaker request").contains(e.getMessage()))events.log("DMSDP 协议拒绝原因："+e.getMessage()+"（固定分类，不含报文内容）。");
            failed=true;close();throw e;
        }
    }
    private void parse(List<byte[]> replies)throws IOException,GeneralSecurityException{
        while(text.length>0){
            int end=-1;for(int i=0;i+3<text.length;i++)if(text[i]==13&&text[i+1]==10&&text[i+2]==13&&text[i+3]==10){end=i;break;}
            if(end<0){if(text.length>8192)throw new IOException("header limit");return;}
            if(end>8192)throw new IOException("header limit");
            for(int i=0;i<end;i++)if((text[i]&255)>127||text[i]==0)throw new IOException("non-ascii header");
            String[] rows=new String(text,0,end,StandardCharsets.US_ASCII).split("\r\n",-1);
            String start=rows[0];
            if(!start.matches("[A-Z_]+ \\S+ DMSDP/1\\.0|DMSDP/1\\.0 [1-5][0-9]{2}( [ -~]+)?"))throw new IOException("start line");
            Map<String,String> headers=new LinkedHashMap<>();
            for(int i=1;i<rows.length;i++){
                int colon=rows[i].indexOf(':');if(colon<1)throw new IOException("header");
                String name=rows[i].substring(0,colon),value=rows[i].substring(colon+1).trim();
                if(!name.matches("[A-Za-z][A-Za-z0-9-]{0,63}")||headers.put(name.toLowerCase(Locale.ROOT),value)!=null||headers.size()>64)throw new IOException("duplicate header");
            }
            String bodySize=headers.containsKey("content-length")?headers.get("content-length"):"0";
            if(!bodySize.matches("[0-9]{1,5}"))throw new IOException("body length");int size=Integer.parseInt(bodySize);
            if(size>65536)throw new IOException("body limit");if(text.length<end+4+size)return;
            byte[] body=Arrays.copyOfRange(text,end+4,end+4+size);text=Arrays.copyOfRange(text,end+4+size,text.length);
            // Log protocol method and length only; no header values or body.
            events.log("DMSDP 控制消息："+start.split(" ",2)[0]+"，正文 "+size+" 字节。");
            if(headers.containsKey("serviceid")&&textHandler!=null){
                if(!negotiated)throw new IOException("speaker before encryption");
                List<byte[]> audio;try{audio=textHandler.receive(start,headers,body);}finally{Arrays.fill(body,(byte)0);}
                if(audio!=null){for(byte[] reply:audio)replies.add(pack(cipher.encrypt(reply),171));continue;}
            }
            Arrays.fill(body,(byte)0);
            String cseq=headers.get("cseq");
            if(start.equals("SET_PARAMETER * DMSDP/1.0")&&headers.containsKey("cryptoswitch")){
                if(negotiated||size!=0||!"1".equals(headers.get("cryptover"))||!"222".equals(headers.get("cryptoswitch"))||!"aes128-ctr".equals(headers.get("cryptoalg"))||cseq==null||!cseq.matches("[0-9]{1,9}"))throw new IOException("crypto negotiation");
                replies.add(pack(encode("DMSDP/1.0 200 OK","CSeq",cseq,"CryptoVer","1","CryptoSwitch","222","CryptoAlg","aes128-ctr","Platform","0"),0));
                negotiated=true;events.log("DMSDP AES-CTR 控制协商完成；等待设备信息交换；尚未宣布媒体服务。");
            }else if(start.equals("GET_PARAMETER * DMSDP/1.0")&&negotiated&&size==0&&headers.size()==1&&cseq!=null&&cseq.matches("[0-9]{1,9}")){
                events.log("DMSDP 收到加密心跳。");
            }else if(start.equals("SET_PARAMETER * DMSDP/1.0")&&negotiated&&headers.keySet().containsAll(Arrays.asList("timeserverport","timeushigh","timeuslow"))){
                if(cseq==null||!cseq.matches("[0-9]{1,9}"))throw new IOException("clock sequence");
                if(!headers.get("timeserverport").matches("[0-9]{1,5}"))throw new IOException("clock port");
                for(String name:new String[]{"timeushigh","timeuslow"}){
                    String value=headers.get(name);if(!value.matches("-?[0-9]{1,10}"))throw new IOException("clock value");
                    long word=Long.parseLong(value);if(word<Integer.MIN_VALUE||word>0xffffffffL)throw new IOException("clock value");
                }
                long port=Long.parseLong(headers.get("timeserverport"));if(port<1||port>65535)throw new IOException("clock port");
                replies.add(pack(cipher.encrypt(encode("DMSDP/1.0 200 OK","CSeq",cseq)),171));events.clock(Long.parseLong(headers.get("timeushigh")),Long.parseLong(headers.get("timeuslow")));events.log("DMSDP 时间参数已应答；未记录参数值。");
            }
        }
    }
    public void close(){cipher.close();Arrays.fill(wire,(byte)0);Arrays.fill(text,(byte)0);wire=new byte[0];text=new byte[0];failed=true;}
}
