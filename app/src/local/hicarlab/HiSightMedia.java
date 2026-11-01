package local.hicarlab;
// CarLink hisight_media.py (MIT): TCP/RTP/PES encrypted elementary stream.
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;

final class HiSightMedia implements AutoCloseable {
    static final int LIMIT=2*1024*1024;
    interface Frames {void frame(int kind,long timestamp,byte[] data)throws IOException;}
    private final SessionCipher cipher;
    private final ByteArrayOutputStream parts=new ByteArrayOutputStream();
    private int sequence=-1;private long timestamp=-1;
    HiSightMedia(byte[] key){cipher=new SessionCipher(key,LIMIT);}
    void packet(byte[] p,Frames frames)throws IOException,GeneralSecurityException{
        if(p.length<12||(p[0]&255)>>>6!=2||(p[0]&32)!=0)throw new IOException("media RTP header");
        int kind=p[1]&127;if(kind<96||kind>98)throw new IOException("media RTP kind");
        int seq=((p[2]&255)<<8)|(p[3]&255);long ts=0;for(int i=4;i<8;i++)ts=(ts<<8)|(p[i]&255);
        if(sequence>=0&&seq!=((sequence+1)&65535)||timestamp>=0&&timestamp!=ts)parts.reset();sequence=seq;timestamp=ts;
        int offset=12+(p[0]&15)*4;
        if((p[0]&16)!=0){if(kind==96){if(offset+4>p.length)throw new IOException("media extension");offset+=4+((p[offset+2]&255)<<8)+(p[offset+3]&255);}else offset++;}
        if(offset>=p.length||parts.size()+p.length-offset>LIMIT)throw new IOException("media payload limit");
        parts.write(p,offset,p.length-offset);if((p[1]&128)==0)return;
        byte[] pes=parts.toByteArray();parts.reset();
        try{
            if(pes.length<14||pes[0]!=0||pes[1]!=0||pes[2]!=1||pes[6]!=(byte)132)throw new IOException("media PES header");
            int size=((pes[4]&255)<<8)|(pes[5]&255);if(size!=0&&size+6!=pes.length)throw new IOException("media PES length");
            int start=14;if((pes[7]&1)!=0&&pes[8]!=0)start+=(pes[8]&255)+1;
            if(start+16>=pes.length)throw new IOException("media encrypted payload");
            byte[] data=cipher.decrypt(Arrays.copyOfRange(pes,start,pes.length));
            try{frames.frame(kind,ts,data);}finally{Arrays.fill(data,(byte)0);}
        }finally{Arrays.fill(pes,(byte)0);}
    }
    public void close(){cipher.close();parts.reset();}
}
