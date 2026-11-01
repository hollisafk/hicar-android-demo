package local.hicarlab;
import java.io.IOException;

final class MicrophoneWire {
    static byte[] packet(byte[] raw,int sequence,long timestamp)throws IOException{
        if(raw==null||raw.length==0||raw.length>8184)throw new IOException("microphone AAC size");
        int length=raw.length+7;byte[] out=new byte[12+length];
        out[0]=(byte)128;out[1]=96;out[2]=(byte)(sequence>>>8);out[3]=(byte)sequence;
        for(int i=0;i<4;i++)out[4+i]=(byte)(timestamp>>>(24-i*8));
        out[10]=4;out[11]=76; // SSRC 1100, native virtual microphone stream.
        int p=12;out[p]=(byte)255;out[p+1]=(byte)241;out[p+2]=76;out[p+3]=(byte)(128|(length>>>11));out[p+4]=(byte)(length>>>3);out[p+5]=(byte)((length&7)<<5|31);out[p+6]=(byte)252;
        System.arraycopy(raw,0,out,19,raw.length);return out;
    }
    static long timestamp(long peerUs,long ptsUs){return ((peerUs/100+ptsUs/100)*9+(peerUs%100+ptsUs%100)*9/100)&0xffffffffL;}
}
