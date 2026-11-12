package local.hicarlab;

// CarLink hisight.py session semantics (MIT), video-only capabilities.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.regex.*;

final class HiSightWire implements AutoCloseable {
    interface Events {void log(String text);}
    private final boolean inputEnabled;
    private final SessionCipher cipher; private final Events events;
    private byte[] buffer=new byte[0];
    private boolean encrypted,options,failed,teardown;
    private String target="*",setupSequence;
    private int sequence=2,mediaPort,inputPort;
    HiSightWire(byte[] key,Events e){this(key,e,false);}
    HiSightWire(byte[] key,Events e,boolean inputs){cipher=new SessionCipher(key,65001);events=e;inputEnabled=inputs;}
    int mediaPort(){return mediaPort;}
    int inputPort(){return inputPort;}
    boolean ended(){return teardown;}
    private static byte[] message(String start,String seq,String body,String... headers)throws IOException{
        if(!seq.matches("[0-9]{1,9}"))throw new IOException("rtsp sequence");
        StringBuilder b=new StringBuilder(start).append("\r\nCSeq: ").append(seq).append("\r\n");
        for(int i=0;i<headers.length;i+=2)b.append(headers[i]).append(": ").append(headers[i+1]).append("\r\n");
        if(!body.isEmpty())b.append("Content-Type: text/parameters\r\nContent-Length: ").append(body.getBytes(StandardCharsets.US_ASCII).length).append("\r\n");
        return b.append("\r\n").append(body).toString().getBytes(StandardCharsets.US_ASCII);
    }
    private byte[] pack(byte[] plain,boolean forcePlain)throws IOException,GeneralSecurityException{
        byte[] p=encrypted&&!forcePlain?cipher.encrypt(plain):plain;
        if(p.length<1||p.length>65000)throw new IOException("rtsp record");
        return NearbyWire.join(new byte[]{(byte)(p.length>>>24),(byte)(p.length>>>16),(byte)(p.length>>>8),(byte)p.length},p);
    }
    byte[] initial()throws IOException,GeneralSecurityException{return pack(message("ANNOUNCE * RTSP/1.0","1","encrypt_description: encrypt_list=aes128ctr;version=1\r\n"),true);}
    byte[] play()throws IOException,GeneralSecurityException{
        if(mediaPort==0)throw new IOException("rtsp media state");
        return pack(message("PLAY "+target+" RTSP/1.0",Integer.toString(++sequence),"","Transport","RTP/AVP/UDP;unicast;client_port=0"),false);
    }
    byte[] stop()throws IOException,GeneralSecurityException{
        if(!encrypted||mediaPort==0||teardown)return null;
        return pack(message("TEARDOWN "+target+" RTSP/1.0",Integer.toString(++sequence),""),false);
    }
    List<byte[]> feed(byte[] data,int n)throws IOException,GeneralSecurityException{
        if(failed||n<0||n>data.length||buffer.length+n>70000)throw new IOException("rtsp buffer");
        List<byte[]> replies=new ArrayList<>();
        try{
            buffer=NearbyWire.join(buffer,Arrays.copyOf(data,n));
            while(buffer.length>=4){
                long size=0;for(int i=0;i<4;i++)size=(size<<8)|(buffer[i]&255);
                if(size<1||size>65000)throw new IOException("rtsp size");if(buffer.length<size+4)break;
                byte[] payload=Arrays.copyOfRange(buffer,4,(int)size+4);buffer=Arrays.copyOfRange(buffer,(int)size+4,buffer.length);
                if(encrypted)payload=cipher.decrypt(payload);
                try{parse(payload,replies);}finally{Arrays.fill(payload,(byte)0);}
            }
            return replies;
        }catch(IOException|GeneralSecurityException|RuntimeException e){
            if(e instanceof IOException&&e.getMessage()!=null&&e.getMessage().matches("rtsp [a-z ]{1,40}"))events.log("HiSight 协议拒绝分类："+e.getMessage()+"（不含报文内容）。");
            failed=true;throw e;
        }
    }
    private void parse(byte[] payload,List<byte[]> replies)throws IOException,GeneralSecurityException{
        for(byte b:payload)if((b&255)>127||b==0)throw new IOException("rtsp ascii");
        String raw=new String(payload,StandardCharsets.US_ASCII);int end=raw.indexOf("\r\n\r\n");
        String head=end<0?raw.trim():raw.substring(0,end),body=end<0?"":raw.substring(end+4);
        if(head.length()>8192||body.length()>56000)throw new IOException("rtsp message limit");
        String[] rows=head.split("\r\n");String start=rows[0];
        if(!start.matches("[A-Z_]+ \\S+ RTSP/1\\.0|RTSP/1\\.0 [1-5][0-9]{2}( [ -~]+)?"))throw new IOException("rtsp start");
        Map<String,String> headers=new LinkedHashMap<>();
        for(int i=1;i<rows.length;i++){
            int p=rows[i].indexOf(':');if(p<1)throw new IOException("rtsp header");String name=rows[i].substring(0,p).toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z][a-z0-9-]{0,63}")||headers.put(name,rows[i].substring(p+1).trim())!=null||headers.size()>64)throw new IOException("rtsp duplicate header");
        }
        String seq=headers.get("cseq");if(seq==null||!seq.matches("[0-9]{1,9}"))throw new IOException("rtsp sequence");
        String method=start.split(" ",2)[0];if(method.equals("RTSP/1.0"))events.log("HiSight RTSP 响应状态 "+start.split(" ")[1]+"，序号 "+seq+"。");events.log("HiSight RTSP 消息："+method+"，正文 "+body.length()+" 字节。");
        if(method.equals("ANNOUNCE")){
            if(encrypted||!body.contains("encrypt_list=aes128ctr"))throw new IOException("rtsp cipher selection");
            replies.add(pack(message("RTSP/1.0 200 OK",seq,""),true));encrypted=true;events.log("HiSight RTSP 加密已协商。");
        }else if(!encrypted){
            if(method.equals("RTSP/1.0")&&seq.equals("1")&&start.startsWith("RTSP/1.0 200 ")){events.log("HiSight 初始 ANNOUNCE 已获手机确认，等待加密声明。");return;}
            throw new IOException("rtsp before encryption");
        }
        else if(method.equals("OPTIONS")){
            replies.add(pack(message("RTSP/1.0 200 OK",seq,"","Public","hisight1.0, GET_PARAMETER, SET_PARAMETER"),false));
            if(!options){replies.add(pack(message("OPTIONS * RTSP/1.0","2","","Require","hisight1.0"),false));options=true;}
        }else if(method.equals("GET_PARAMETER")){
            Set<String> requested=new HashSet<>();for(String row:body.split("\r?\n")){String k=row.split(":",2)[0].trim();if(!k.isEmpty())requested.add(k);}
            Map<String,String> caps=new LinkedHashMap<>();caps.put("his_version","1.0");
            caps.put("his_video_formats",VideoSpec.formats());
            caps.put("his_device_type","device_type 4;subtype 0;");
            if(inputEnabled)caps.put("his_uibc_capability","input_category_list=GENERIC;generic_cap_list=left_point_down, left_point_up, left_move, left_key_down, left_key_up;");
            StringBuilder answer=new StringBuilder();for(Map.Entry<String,String> c:caps.entrySet())if(requested.contains(c.getKey()))answer.append(c.getKey()).append(": ").append(c.getValue()).append("\r\n");
            replies.add(pack(message("RTSP/1.0 200 OK",seq,answer.toString()),false));
        }else if(method.equals("SET_PARAMETER")){
            Map<String,String> params=new HashMap<>();for(String row:body.split("\r?\n")){int p=row.indexOf(':');if(p>0){String k=row.substring(0,p).trim().toLowerCase(Locale.ROOT);if(params.put(k,row.substring(p+1).trim())!=null)throw new IOException("rtsp duplicate parameter");}}
            if(params.containsKey("his_presentation_url")){
                String url=params.get("his_presentation_url").split(" ")[0];if(!url.matches("rtsp://[A-Za-z0-9.:/_?=&%=-]{1,256}"))throw new IOException("rtsp URL");target=url;
            }
            replies.add(pack(message("RTSP/1.0 200 OK",seq,""),false));
            if("SETUP".equals(params.get("his_trigger_method"))){
                if(setupSequence!=null)throw new IOException("rtsp duplicate setup");
                setupSequence=Integer.toString(++sequence);replies.add(pack(message("SETUP "+target+" RTSP/1.0",setupSequence,"","Transport","RTP/AVP/UDP;unicast;client_port=0"),false));
            }else if("TEARDOWN".equals(params.get("his_trigger_method")))teardown=true;
        }else if(method.equals("TEARDOWN")){replies.add(pack(message("RTSP/1.0 200 OK",seq,""),false));teardown=true;}
        else if(method.equals("RTSP/1.0")&&seq.equals(setupSequence)){
            if(!start.startsWith("RTSP/1.0 200 "))throw new IOException("rtsp setup refused");
            Matcher port=Pattern.compile("(?:^|;)server_port=([0-9]+)").matcher(getStr(headers,"transport"));
            if(!port.find())throw new IOException("rtsp media port");int p=Integer.parseInt(port.group(1));if(p<1||p>65535)throw new IOException("rtsp media port");
            Matcher remote=Pattern.compile("(?:^|;)remotectl_port=([0-9]+)").matcher(getStr(headers,"transport"));
            if(remote.find()){int input=Integer.parseInt(remote.group(1));if(input<1||input>65535)throw new IOException("rtsp input port");inputPort=input;}
            mediaPort=p;events.log("HiSight 视频端口已协商；仅向本轮已认证手机连接。");
        }
    }
    public void close(){cipher.close();Arrays.fill(buffer,(byte)0);buffer=new byte[0];failed=true;}
    /** Android 5.x(API22) 兼容：Map.getOrDefault 需要 API 24。 */
    private static String getStr(java.util.Map<String,String> m, String k) { return m.containsKey(k) ? m.get(k) : ""; }
}
