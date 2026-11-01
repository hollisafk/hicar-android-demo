package local.hicarlab;

// CarLink audio_media.py/audio_transport.py (MIT). No media capture/history.
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;

final class AudioMedia {
    static final int[] RATES={96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
    static final class Frame {
        final byte[] data;final int rate,rateIndex,channels,samples,stream;final long timestamp,offset;
        Frame(byte[] d,int r,int c,int samples,int stream,long t,long offset){data=d;rateIndex=r;rate=RATES[r];channels=c;this.samples=samples;this.stream=stream;timestamp=t;this.offset=offset;}
        Frame at(int stream,long timestamp,long offset){return new Frame(data,rateIndex,channels,samples,stream,timestamp,offset);}
    }
    private static final class Packet {long timestamp;List<Frame> frames;Packet(long t,List<Frame> f){timestamp=t;frames=f;}}
    private static final class Stream {int expected,rate;long timestamp=-1,offset;Map<Integer,Packet> pending=new HashMap<>();Stream(int sequence){expected=sequence;}}
    private final Map<Integer,Stream> streams=new HashMap<>();
    long packets,frames,lost,duplicates;
    static byte[] decrypt(byte[] data,SessionCipher cipher)throws IOException,GeneralSecurityException{
        if(data.length<20||data[0]!=(byte)171||(((data[1]&255)<<8)|(data[2]&255))!=data.length-3)throw new IOException("audio UDP record");
        return cipher.decrypt(Arrays.copyOfRange(data,3,data.length));
    }
    private static List<Frame> adts(byte[] p,int start,int end)throws IOException{
        List<Frame> output=new ArrayList<>();
        for(int at=start;at<end;){
            if(end-at<7||p[at]!=(byte)255||(p[at+1]&246)!=240)throw new IOException("audio ADTS header");
            int profile=((p[at+2]>>>6)&3)+1,rate=(p[at+2]>>>2)&15,channels=((p[at+2]&1)<<2)|((p[at+3]>>>6)&3);
            int length=((p[at+3]&3)<<11)|((p[at+4]&255)<<3)|((p[at+5]>>>5)&7),header=(p[at+1]&1)!=0?7:9;
            if(profile!=2||rate>=RATES.length||(channels!=1&&channels!=2)||(p[at+6]&3)!=0||length<=header||length>8191||at+length>end)throw new IOException("audio ADTS format");
            output.add(new Frame(Arrays.copyOfRange(p,at+header,at+length),rate,channels,1024,0,0,0));at+=length;
        }
        if(output.isEmpty())throw new IOException("audio empty payload");return output;
    }
    synchronized List<Frame> receive(byte[] p)throws IOException{
        if(p.length<12||(p[0]&255)>>>6!=2||(p[1]&127)!=96)throw new IOException("audio RTP header");
        int sequence=((p[2]&255)<<8)|(p[3]&255);long timestamp=0;for(int i=4;i<8;i++)timestamp=(timestamp<<8)|(p[i]&255);
        int stream=((p[10]&15)<<8)|(p[11]&255); // native stream key is SSRC & 0xfff
        int start=12+(p[0]&15)*4,end=p.length;
        if(start>end)throw new IOException("audio CSRC");
        if((p[0]&16)!=0){if(start+4>end)throw new IOException("audio extension");start+=4+4*(((p[start+2]&255)<<8)|(p[start+3]&255));}
        if((p[0]&32)!=0){int padding=p[p.length-1]&255;if(padding==0||padding>end-start)throw new IOException("audio padding");end-=padding;}
        if(start>=end)throw new IOException("audio empty RTP");List<Frame> decoded=adts(p,start,end);
        Stream state=streams.get(stream);if(state==null){if(streams.size()>=8)throw new IOException("audio stream limit");state=new Stream(sequence);streams.put(stream,state);}
        int distance=(sequence-state.expected)&65535;
        if(distance>=32768||state.pending.containsKey(sequence)){duplicates++;wipe(decoded);return Collections.emptyList();}
        if(distance>16){lost+=distance;state.expected=sequence;for(Packet old:state.pending.values())wipe(old.frames);state.pending.clear();}
        state.pending.put(sequence,new Packet(timestamp,decoded));
        if(state.pending.size()>=8&&!state.pending.containsKey(state.expected)){
            int nearest=sequence,best=65536;for(int s:state.pending.keySet()){int d=(s-state.expected)&65535;if(d<best){nearest=s;best=d;}}
            lost+=best;state.expected=nearest;
        }
        List<Frame> ready=new ArrayList<>();
        while(state.pending.containsKey(state.expected)){
            Packet packet=state.pending.remove(state.expected);packets++;
            for(Frame frame:packet.frames){if(state.timestamp!=packet.timestamp||state.rate!=frame.rate){state.timestamp=packet.timestamp;state.rate=frame.rate;state.offset=0;}
                ready.add(frame.at(stream,packet.timestamp,state.offset));state.offset+=frame.samples;frames++;
            }
            state.expected=(state.expected+1)&65535;
        }
        return ready;
    }
    private static void wipe(List<Frame> frames){for(Frame frame:frames)Arrays.fill(frame.data,(byte)0);}
    synchronized void reset(){for(Stream s:streams.values())for(Packet p:s.pending.values())wipe(p.frames);streams.clear();}
}
