package local.hicarlab;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** mic1 only: phone controls OPTIONS -> crypto -> SETUP -> PLAY. */
final class MicrophoneControl implements ControlSession.TextHandler {
    interface Output {int port();boolean allowed();void start(int port)throws IOException;void stop();void log(String text);}
    private final Output output;
    private int session=-1,sequence,phase,remote;
    private final ArrayDeque<Integer> retired=new ArrayDeque<>();
    MicrophoneControl(Output output){this.output=output;}
    private byte[] response(String result,String seq,int id,String... extra){
        List<String> fields=new ArrayList<>(Arrays.asList("ServiceID","mic1","ServiceType","2","DataSessionID",Integer.toString(id),"CSeq",seq));fields.addAll(Arrays.asList(extra));
        return ControlSession.encode("DMSDP/1.0 "+result,fields.toArray(new String[0]));
    }
    private void retire(){output.stop();if(session>0){if(retired.size()>=16)retired.removeFirst();retired.addLast(session);}session=-1;phase=0;remote=0;}
    public List<byte[]> receive(String start,Map<String,String> h,byte[] body)throws IOException{
        if(!"mic1".equals(h.get("serviceid")))return null;
        String seq=h.get("cseq"),sid=h.get("datasessionid");
        if(!"2".equals(h.get("servicetype"))||seq==null||!seq.matches("[0-9]{1,9}")||sid==null||!sid.matches("[0-9]{1,10}")||Long.parseLong(sid)>Integer.MAX_VALUE)throw new IOException("microphone control fields");
        int id=Integer.parseInt(sid);List<byte[]> replies=new ArrayList<>();
        String text=new String(body,StandardCharsets.US_ASCII);
        Matcher trigger=Pattern.compile("msdp_trigger_method:\\s*([A-Z]+)\\s*").matcher(text);
        if(trigger.matches()){
            if(!start.startsWith("SET_PARAMETER "))throw new IOException("microphone trigger");
            String method=trigger.group(1);
            if(method.equals("OPTIONS")){
                if(id==session||retired.contains(id))return replies;
                if(!output.allowed()){replies.add(response("403 Forbidden",seq,id));return replies;}
                if(id==0||output.port()<1||output.port()>65535||!Arrays.asList("0","1").contains(h.get("simple")))throw new IOException("microphone setup mode");
                retire();session=id;phase=1;
                replies.add(ControlSession.encode("OPTIONS * DMSDP/1.0","ServiceID","mic1","ServiceType","2","DataSessionID",sid,"CSeq",Integer.toString(++sequence),"Simple","0","CryptoVer","1","CryptoAbility","222","CryptoAlg","aes128-ctr","Fillp","0","TCP","0","Codec","0"));
                output.log("麦克风：已响应手机语音请求，等待上行传输协商；尚未录音。");
            }else if(method.equals("CLOSE")||method.equals("TEARDOWN")){if(id==session){if(method.equals("TEARDOWN"))replies.add(ControlSession.encode("TEARDOWN * DMSDP/1.0","ServiceID","mic1","ServiceType","2","DataSessionID",sid,"CSeq",Integer.toString(++sequence)));retire();}}
            else throw new IOException("microphone trigger");
            return replies;
        }
        if(start.startsWith("DMSDP/1.0 "))return replies;
        if(id!=session)return replies;
        if(start.startsWith("TEARDOWN ")){replies.add(response("200 OK",seq,id));retire();return replies;}
        if(!output.allowed()){retire();replies.add(response("403 Forbidden",seq,id));return replies;}
        if(start.startsWith("SET_PARAMETER ")&&h.containsKey("cryptoswitch")){
            if(phase==0||!"1".equals(h.get("cryptover"))||!"222".equals(h.get("cryptoswitch"))||!"aes128-ctr".equals(h.get("cryptoalg"))||body.length!=0)throw new IOException("microphone crypto");
            replies.add(response("200 OK",seq,id,"CryptoVer","1"));if(phase==1)phase=2;
        }else if(start.startsWith("SETUP ")){
            if(phase<2||body.length!=0)throw new IOException("microphone setup order");
            Matcher transport=Pattern.compile("RTP/AVP/UDP;unicast;client_port=([0-9]{1,5})-(-1|[0-9]{1,5})").matcher(h.getOrDefault("transport",""));
            if(!transport.matches())throw new IOException("microphone transport");
            int port=Integer.parseInt(transport.group(1)),rtcp=Integer.parseInt(transport.group(2));
            if(port<1||port>65535||rtcp==0||rtcp>65535||phase>2&&remote!=port)throw new IOException("microphone endpoint");
            remote=port;if(phase==2)phase=3;
            replies.add(response("200 OK",seq,id,"Transport",h.get("transport")+";server_port="+output.port()+"--1"));
        }else if(start.startsWith("PLAY ")){
            if(phase<3||body.length!=0)throw new IOException("microphone play order");
            if(phase!=4){try{output.start(remote);phase=4;}catch(IOException|RuntimeException e){retire();output.log("麦克风未能启动，画面和播放继续。");replies.add(response("503 Service Unavailable",seq,id));return replies;}}replies.add(response("200 OK",seq,id));
        }else if(start.startsWith("SET_PARAMETER ")&&"SampleRate=48000;Format=16;ChannelNum=2;Codec=0".equals(h.get("audioparameter"))&&body.length==0)replies.add(response("200 OK",seq,id));
        else if(start.startsWith("GET_PARAMETER ")&&body.length==0)replies.add(response("200 OK",seq,id));
        else throw new IOException("microphone request");
        return replies;
    }
}
