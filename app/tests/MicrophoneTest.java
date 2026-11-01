package local.hicarlab;
import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;
public final class MicrophoneTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static final class Out implements MicrophoneControl.Output {int starts,stops,remote;boolean allowed=true;public int port(){return 43000;}public boolean allowed(){return allowed;}public void start(int p){starts++;remote=p;}public void stop(){stops++;}public void log(String s){}}
 static Map<String,String> h(int id,String... fields){Map<String,String> h=new HashMap<>();h.put("serviceid","mic1");h.put("servicetype","2");h.put("cseq","1");h.put("datasessionid",Integer.toString(id));for(int i=0;i<fields.length;i+=2)h.put(fields[i],fields[i+1]);return h;}
 static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
 public static void main(String[] args)throws Exception{
  Out out=new Out();MicrophoneControl c=new MicrophoneControl(out);
  List<byte[]> r=c.receive("SET_PARAMETER * DMSDP/1.0",h(7,"simple","1"),b("msdp_trigger_method: OPTIONS"));
  check(new String(r.get(0),StandardCharsets.US_ASCII).startsWith("OPTIONS")&&new String(r.get(0),StandardCharsets.US_ASCII).contains("Simple: 0")&&out.starts==0);
  boolean bad=false;try{c.receive("PLAY * DMSDP/1.0",h(7),new byte[0]);}catch(IOException e){bad=true;}check(bad&&out.starts==0);
  c.receive("SET_PARAMETER * DMSDP/1.0",h(7,"cryptover","1","cryptoswitch","222","cryptoalg","aes128-ctr"),new byte[0]);
  r=c.receive("SETUP * DMSDP/1.0",h(7,"transport","RTP/AVP/UDP;unicast;client_port=42000--1"),new byte[0]);check(new String(r.get(0),StandardCharsets.US_ASCII).contains("server_port=43000--1"));
  c.receive("PLAY * DMSDP/1.0",h(7),new byte[0]);c.receive("PLAY * DMSDP/1.0",h(7),new byte[0]);check(out.starts==1&&out.remote==42000);
  c.receive("SET_PARAMETER * DMSDP/1.0",h(8,"simple","1"),b("msdp_trigger_method: OPTIONS"));int stopped=out.stops;
  c.receive("SET_PARAMETER * DMSDP/1.0",h(7),b("msdp_trigger_method: CLOSE"));check(out.stops==stopped);
  c.receive("SETUP * DMSDP/1.0",h(7,"transport","RTP/AVP/UDP;unicast;client_port=44444--1"),new byte[0]);check(out.starts==1);
  out.allowed=false;r=c.receive("PLAY * DMSDP/1.0",h(8),new byte[0]);check(new String(r.get(0),StandardCharsets.US_ASCII).contains("403")&&out.starts==1);
  byte[] raw={1,2,3,4};byte[] packet=MicrophoneWire.packet(raw,65535,0xffffffffL);
  check((packet[2]&255)==255&&(packet[3]&255)==255&&packet[10]==4&&packet[11]==76);
  AudioMedia.Frame f=new AudioMedia().receive(packet).get(0);check(f.rate==48000&&f.channels==2&&f.stream==1100&&Arrays.equals(f.data,raw));
  check(MicrophoneWire.timestamp(123000000L,0)==11070000L&&MicrophoneWire.timestamp(123000000L,21333)==11071919L);
  byte[] key=new byte[32];try(SessionCipher sender=new SessionCipher(key,65536);SessionCipher peer=new SessionCipher(key,65536)){
   byte[] one=sender.encrypt(packet),two=sender.encrypt(packet);check(!Arrays.equals(one,two)&&Arrays.equals(packet,peer.decrypt(one)));
  }
  System.out.println("PASS: mic OPTIONS/crypto/SETUP/PLAY, no capture before PLAY or without permission, stale-session close ignored, AAC ADTS/RTP SSRC1100 and fresh encryption IVs");
 }
}
