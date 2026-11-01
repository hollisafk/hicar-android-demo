package local.hicarlab;

// CarLink audio_wire.py (MIT): Simple speaker transport, encrypted by parent.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

final class SpeakerControl implements ControlSession.TextHandler {
    interface Output {int port();void begin();void ready(int port)throws IOException;void format(int rate,int channels);void stop();void log(String text);}
    private final Output output;
    private int session=-1,sequence,setup;
    SpeakerControl(Output o){output=o;}
    private byte[] request(String method,String... extra){
        List<String> fields=new ArrayList<>(Arrays.asList("ServiceID","speaker1","ServiceType","3","DataSessionID",Integer.toString(session),"CSeq",Integer.toString(++sequence)));
        fields.addAll(Arrays.asList(extra));return ControlSession.encode(method+" * DMSDP/1.0",fields.toArray(new String[0]));
    }
    private static byte[] ack(String cseq,int sid){return ControlSession.encode("DMSDP/1.0 200 OK","ServiceID","speaker1","ServiceType","3","DataSessionID",Integer.toString(sid),"CSeq",cseq);}
    public List<byte[]> receive(String start,Map<String,String> headers,byte[] body)throws IOException{
        if(!"speaker1".equals(headers.get("serviceid")))return null;
        String cseq=headers.get("cseq"),sid=headers.get("datasessionid");
        if(!"3".equals(headers.get("servicetype"))||cseq==null||!cseq.matches("[0-9]{1,9}")||sid==null||!sid.matches("[0-9]{1,10}")||Long.parseLong(sid)>Integer.MAX_VALUE)throw new IOException("speaker control fields");
        int id=Integer.parseInt(sid);List<byte[]> replies=new ArrayList<>();
        String text=new String(body,StandardCharsets.US_ASCII);Matcher trigger=Pattern.compile("msdp_trigger_method:\\s*([A-Z]+)\\s*").matcher(text);
        if(trigger.matches()){
            if(!start.startsWith("SET_PARAMETER "))throw new IOException("speaker trigger state");String method=trigger.group(1);
            if(method.equals("OPTIONS")){
                if(output.port()<1||output.port()>65535||!"1".equals(headers.get("simple"))||id==0)throw new IOException("speaker simple mode");
                if(id==session)return replies;
                output.begin();session=id;
                replies.add(request("SETUP","Transport","RTP/AVP/UDP;unicast;client_port="+output.port()+"--1","Simple","1","CryptoVer","1","CryptoAbility","222","CryptoAlg","aes128-ctr","Fillp","0","TCP","0","Codec","0"));setup=sequence;
                output.log("扬声器 SETUP 已发送：AAC/UDP，加密，禁用 RTCP；尚未收到音频。");
            }else if(method.equals("TEARDOWN")||method.equals("CLOSE")){
                if(id==session){if(method.equals("TEARDOWN"))replies.add(request("TEARDOWN"));session=-1;output.stop();output.log("手机已结束当前音频会话。");}
            }else throw new IOException("speaker trigger");
            return replies;
        }
        if(start.startsWith("DMSDP/1.0 ")){
            if(id!=session||Integer.parseInt(cseq)!=setup)return replies;
            if(!start.startsWith("DMSDP/1.0 200 "))throw new IOException("speaker setup refused");
            Matcher transport=Pattern.compile("RTP/AVP/UDP;unicast;client_port=([0-9]+)--1;server_port=([0-9]+)--1").matcher(headers.getOrDefault("transport",""));
            if(!transport.matches()||Integer.parseInt(transport.group(1))!=output.port())throw new IOException("speaker transport");int port=Integer.parseInt(transport.group(2));if(port<1||port>65535)throw new IOException("speaker remote port");
            output.ready(port);output.log("扬声器 UDP 传输已协商，等待加密音频。");
        }else if(id!=session)throw new IOException("speaker session mismatch");
        else if(start.startsWith("SET_PARAMETER ")){
            Matcher parameters=Pattern.compile("SampleRate=(48000|44100|32000|24000|16000|8000);Format=16;ChannelNum=([12]);Codec=0").matcher(headers.getOrDefault("audioparameter",""));
            if(!parameters.matches()||body.length!=0)throw new IOException("speaker audio format");
            int rate=Integer.parseInt(parameters.group(1)),channels=Integer.parseInt(parameters.group(2));output.format(rate,channels);replies.add(ack(cseq,id));output.log("手机音频参数：AAC，"+rate+" Hz，"+channels+" 声道。");
        }else if(start.startsWith("TEARDOWN ")){replies.add(ack(cseq,id));session=-1;output.stop();}
        else if(start.startsWith("GET_PARAMETER ")&&body.length==0)replies.add(ack(cseq,id));
        else throw new IOException("speaker request");
        return replies;
    }
}
