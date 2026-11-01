package local.hicarlab;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public final class AudioTest {
    static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static byte[] aac(){return new byte[]{-1,(byte)241,76,(byte)128,1,127,(byte)252,0,0,0,0};}
    static byte[] rtp(int sequence,long ssrc,byte[] payload){return NearbyWire.join(new byte[]{(byte)128,96,(byte)(sequence>>>8),(byte)sequence,0,0,0,100,(byte)(ssrc>>>24),(byte)(ssrc>>>16),(byte)(ssrc>>>8),(byte)ssrc},payload);}
    static final class Output implements SpeakerControl.Output {
        int begin,remote,rate,channels,stopped;
        public int port(){return 40000;}public void begin(){begin++;}public void ready(int p){remote=p;}public void format(int r,int c){rate=r;channels=c;}public void stop(){stopped++;}public void log(String s){}
    }
    static byte[] text(String start,byte[] body,String... extra){List<String> headers=new ArrayList<>(Arrays.asList("ServiceID","speaker1","ServiceType","3","DataSessionID","42","CSeq","1"));headers.addAll(Arrays.asList(extra));if(body.length>0){headers.add("Content-Length");headers.add(Integer.toString(body.length));}return NearbyWire.join(ControlSession.encode(start,headers.toArray(new String[0])),body);}
    static byte[] encrypted(ControlSession c,SessionCipher peer,byte[] message)throws Exception{byte[] record=ControlSession.pack(peer.encrypt(message),171);List<byte[]> replies=c.feed(record,record.length);check(replies.size()==1&&replies.get(0)[0]==(byte)171);return peer.decrypt(Arrays.copyOfRange(replies.get(0),3,replies.get(0).length));}
    public static void main(String[] args)throws Exception{
        byte[] key=new byte[32];for(int i=0;i<32;i++)key[i]=(byte)i;
        AudioMedia audio=new AudioMedia();
        check(audio.receive(rtp(65535,0x1003eb,aac())).get(0).rate==48000);
        check(audio.receive(rtp(1,0x1103eb,aac())).isEmpty());
        List<AudioMedia.Frame> frames=audio.receive(rtp(0,0x103eb,NearbyWire.join(aac(),aac())));
        check(frames.size()==3&&frames.get(0).stream==1003&&frames.get(0).channels==2&&frames.get(0).data.length==4);
        check(frames.get(1).offset==2048&&frames.get(2).offset==3072); // same RTP timestamp retains sample offset
        check(audio.receive(rtp(1,0x3eb,aac())).isEmpty()&&audio.duplicates==1);
        audio.receive(rtp(40,0x3eb,aac()));check(audio.lost==38);
        byte[] packet=rtp(0,1,aac());for(int cut=0;cut<packet.length;cut++){boolean rejected=false;try{new AudioMedia().receive(Arrays.copyOf(packet,cut));}catch(IOException e){rejected=true;}check(rejected);}
        AudioMedia bounded=new AudioMedia();for(int i=0;i<8;i++)bounded.receive(rtp(0,i,aac()));boolean reject=false;try{bounded.receive(rtp(0,9,aac()));}catch(IOException e){reject=true;}check(reject);
        Output output=new Output();SpeakerControl speaker=new SpeakerControl(output);
        try(ControlSession control=new ControlSession(key,s->{},null,speaker);SessionCipher peer=new SessionCipher(key,65536)){
            byte[] request=ControlSession.pack(b("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 0\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\n\r\n"),0);control.feed(request,request.length);
            byte[] setup=encrypted(control,peer,text("SET_PARAMETER * DMSDP/1.0",b("msdp_trigger_method: OPTIONS\r\n"),"Simple","1"));
            check(new String(setup,StandardCharsets.US_ASCII).contains("client_port=40000--1")&&output.begin==1);
            if(args.length>0)check(ControlTest.hex(setup).equals(java.nio.file.Files.readAllLines(java.nio.file.Paths.get(args[0])).get(0)));
            byte[] ready=ControlSession.pack(peer.encrypt(text("DMSDP/1.0 200 OK",new byte[0],"Transport","RTP/AVP/UDP;unicast;client_port=40000--1;server_port=44000--1")),171);check(control.feed(ready,ready.length).isEmpty()&&output.remote==44000);
            byte[] ack=encrypted(control,peer,text("SET_PARAMETER * DMSDP/1.0",new byte[0],"AudioParameter","SampleRate=48000;Format=16;ChannelNum=2;Codec=0"));check(output.rate==48000&&output.channels==2&&new String(ack,StandardCharsets.US_ASCII).startsWith("DMSDP/1.0 200"));
            byte[] wire=ControlSession.pack(peer.encrypt(packet),171);check(Arrays.equals(AudioMedia.decrypt(wire,peer),packet));
            byte[] bad=wire.clone();bad[0]=0;boolean invalid=false;try{AudioMedia.decrypt(bad,peer);}catch(IOException e){invalid=true;}check(invalid);
        }
        Map<String,String> sessionHeaders=new HashMap<>();sessionHeaders.put("serviceid","speaker1");sessionHeaders.put("servicetype","3");sessionHeaders.put("datasessionid","43");sessionHeaders.put("cseq","2");sessionHeaders.put("simple","1");
        speaker.receive("SET_PARAMETER * DMSDP/1.0",sessionHeaders,b("msdp_trigger_method: OPTIONS"));check(output.begin==2);
        Map<String,String> stale=new HashMap<>(sessionHeaders);stale.put("datasessionid","42");speaker.receive("SET_PARAMETER * DMSDP/1.0",stale,b("msdp_trigger_method: CLOSE"));check(output.stopped==0);
        Map<String,String> invalid=new HashMap<>(sessionHeaders);invalid.put("audioparameter","SampleRate=48000;Format=16;ChannelNum=2;Codec=9");boolean badFormat=false;try{speaker.receive("SET_PARAMETER * DMSDP/1.0",invalid,new byte[0]);}catch(IOException e){badFormat=true;}check(badFormat);
        List<String> logs=new ArrayList<>();DeviceBusiness d=new DeviceBusiness("own","00048000","192.168.49.1","192.168.49.2",logs::add,null,()->true);
        d.receive(3,0xffffffffL,NearbyWire.join(NearbyWire.uint(101),b("{\"deviceId\":\"peer\"}")));
        byte[] announce=d.receive(3,0xffffffffL,NearbyWire.join(NearbyWire.uint(104),new byte[]{0,0,3,63})).get(0);NearbyWire.Cursor cursor=new NearbyWire.Cursor(announce,11);check(cursor.uint()==102);
        JSONObject service=AuthBusiness.object(new String(announce,cursor.p,announce.length-cursor.p,StandardCharsets.UTF_8));check(service.getInt("serviceType")==4&&service.getString("serviceId").equals("speaker1"));
        // Actual UDP loopback: reject a wrong source and malformed record, then reorder/decrypt AAC.
        CountDownLatch done=new CountDownLatch(3);AtomicInteger count=new AtomicInteger();AtomicBoolean sinkClosed=new AtomicBoolean(),active=new AtomicBoolean(true);
        AudioReceiver.Sink sink=new AudioReceiver.Sink(){public boolean ready(){return true;}public void reset(){}public void offer(AudioMedia.Frame f){check(f.rate==48000&&f.channels==2&&f.data.length==4);count.incrementAndGet();done.countDown();}public void close(){sinkClosed.set(true);}};
        try(DatagramSocket sender=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));DatagramSocket other=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));SessionCipher cipher=new SessionCipher(key,65536)){
            AudioReceiver receiver=new AudioReceiver("127.0.0.1","127.0.0.1",key,sink,new AudioReceiver.Events(){public boolean active(){return active.get();}public void log(String s){logs.add(s);}});
            try{
                receiver.begin();receiver.ready(sender.getLocalPort());int port=receiver.port();byte[] bogus=ControlSession.pack(cipher.encrypt(rtp(2,1,aac())),171);other.send(new DatagramPacket(bogus,bogus.length,InetAddress.getLoopbackAddress(),port));
                sender.send(new DatagramPacket(new byte[0],0,InetAddress.getLoopbackAddress(),port));
                for(int seq:new int[]{65535,1,0}){byte[] wire=ControlSession.pack(cipher.encrypt(rtp(seq,1,aac())),171);sender.send(new DatagramPacket(wire,wire.length,InetAddress.getLoopbackAddress(),port));}
                check(done.await(3,TimeUnit.SECONDS)&&count.get()==3);
            }finally{active.set(false);receiver.close();}
            check(sinkClosed.get());
        }
        System.out.println("PASS: reference speaker SETUP and encrypted control, audio declaration, RTP/ADTS bounds/reorder/SSRC/wrap, real encrypted UDP with source filtering and cleanup");
    }
}
