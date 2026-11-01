package local.hicarlab;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HiSightTest {
    static byte[] key=new byte[32];
    static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static byte[] record(byte[] payload){return NearbyWire.join(new byte[]{(byte)(payload.length>>>24),(byte)(payload.length>>>16),(byte)(payload.length>>>8),(byte)payload.length},payload);}
    static byte[] announce(){return record(b("ANNOUNCE * RTSP/1.0\r\nCSeq: 1\r\n\r\nencrypt_description: encrypt_list=aes128ctr;version=1\r\n"));}
    static byte[] deliver(HiSightWire session,SessionCipher peer,String plain)throws Exception{
        byte[] frame=record(peer.encrypt(b(plain)));List<byte[]> replies=session.feed(frame,frame.length);return replies.isEmpty()?null:replies.get(replies.size()-1);
    }
    public static void main(String[] args)throws Exception{
        for(int i=0;i<key.length;i++)key[i]=(byte)i;
        byte[] a=announce();
        for(int cut=0;cut<=a.length;cut++)try(HiSightWire s=new HiSightWire(key,t->{})){
            List<byte[]> replies=new ArrayList<>();replies.addAll(s.feed(Arrays.copyOfRange(a,0,cut),cut));replies.addAll(s.feed(Arrays.copyOfRange(a,cut,a.length),a.length-cut));
            check(replies.size()==1&&new String(replies.get(0),4,replies.get(0).length-4,StandardCharsets.US_ASCII).startsWith("RTSP/1.0 200 OK"));
        }
        byte[] initialAck=record(b("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"));
        byte[] ackAndAnnounce=NearbyWire.join(initialAck,a);
        for(int split=0;split<=ackAndAnnounce.length;split++)try(HiSightWire session=new HiSightWire(key,t->{})){
            List<byte[]> out=new ArrayList<>();out.addAll(session.feed(Arrays.copyOfRange(ackAndAnnounce,0,split),split));out.addAll(session.feed(Arrays.copyOfRange(ackAndAnnounce,split,ackAndAnnounce.length),ackAndAnnounce.length-split));
            check(out.size()==1);
        }
        try(HiSightWire s=new HiSightWire(key,t->{});SessionCipher peer=new SessionCipher(key,65001)){
            s.feed(a,a.length);
            byte[] cap=deliver(s,peer,"GET_PARAMETER * RTSP/1.0\r\nCSeq: 2\r\n\r\nhis_video_formats\r\nhis_audio_codecs\r\nhis_uibc_capability\r\nhis_vtp\r\n");
            String answer=new String(peer.decrypt(Arrays.copyOfRange(cap,4,cap.length)),StandardCharsets.US_ASCII);
            check(answer.contains("width 800;height 480")&&!answer.contains("his_audio_codecs")&&!answer.contains("his_uibc_capability")&&!answer.contains("his_vtp"));
            byte[] setup=deliver(s,peer,"SET_PARAMETER * RTSP/1.0\r\nCSeq: 3\r\n\r\nhis_presentation_url: rtsp://192.168.49.2/hisight/streamid=0 none\r\nhis_trigger_method: SETUP\r\n");
            check(new String(peer.decrypt(Arrays.copyOfRange(setup,4,setup.length)),StandardCharsets.US_ASCII).startsWith("SETUP rtsp://"));
            deliver(s,peer,"RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Length: 60\r\nTransport: RTP/AVP/UDP;unicast;server_port=49000;remotectl_port=49001\r\n");
            check(s.mediaPort()==49000&&s.inputPort()==49001);byte[] play=s.play();check(new String(peer.decrypt(Arrays.copyOfRange(play,4,play.length)),StandardCharsets.US_ASCII).startsWith("PLAY rtsp://"));
        }
        for(byte[] bad:new byte[][]{new byte[4],new byte[]{0,0,(byte)254,(byte)233},record(b("ANNOUNCE * RTSP/1.0\r\nCSeq: 1\r\n\r\nencrypt_list=aes128gcm"))})try(HiSightWire s=new HiSightWire(key,t->{})){
            boolean rejected=false;try{s.feed(bad,bad.length);}catch(IOException e){rejected=true;}check(rejected);
        }
        byte[] es=new byte[]{0,0,0,1,103,11,22,33};
        try(HiSightMedia m=new HiSightMedia(key);SessionCipher peer=new SessionCipher(key,HiSightMedia.LIMIT)){
            byte[] crypt=peer.encrypt(es);int size=crypt.length+8;
            byte[] pes=NearbyWire.join(new byte[]{0,0,1,(byte)224,(byte)(size>>>8),(byte)size,(byte)132,(byte)128,5,0,0,0,0,0},crypt);
            List<byte[]> frames=new ArrayList<>();
            m.packet(rtp(65535,false,Arrays.copyOfRange(pes,0,18)),(kind,time,data)->frames.add(data.clone()));
            check(frames.isEmpty());m.packet(rtp(0,true,Arrays.copyOfRange(pes,18,pes.length)),(kind,time,data)->{check(kind==96);frames.add(data.clone());});
            check(frames.size()==1&&Arrays.equals(frames.get(0),es));
            m.packet(rtp(1,false,Arrays.copyOfRange(pes,0,18)),(kind,time,data)->{});
            boolean rejected=false;try{m.packet(rtp(3,true,Arrays.copyOfRange(pes,18,pes.length)),(kind,time,data)->{});}catch(IOException e){rejected=true;}check(rejected);
        }
        System.out.println("PASS: every ANNOUNCE split, encrypted video-only capabilities, observed SETUP boundary and endpoint/PLAY, RTP wrap/PES decryption and loss rejection");
    }
    static byte[] rtp(int sequence,boolean marker,byte[] data){return NearbyWire.join(new byte[]{(byte)128,(byte)(96|(marker?128:0)),(byte)(sequence>>>8),(byte)sequence,0,0,0,1,0,0,0,0},data);}
}
