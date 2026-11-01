package local.hicarlab;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;

public final class ControlTest {
    static byte[] key=new byte[32];
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static byte[] negotiate()throws Exception{return ControlSession.pack(bytes("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 0\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\n\r\n"),0);}
    static void check(boolean b){if(!b)throw new AssertionError();}
    static void bad(byte[] input)throws Exception{
        try(ControlSession c=new ControlSession(key,t->{})){
            boolean rejected=false;try{c.feed(input,input.length);}catch(IOException e){rejected=true;}check(rejected);
            try{c.feed(negotiate(),negotiate().length);throw new AssertionError("must remain failed");}catch(IOException expected){}
        }
    }
    public static void main(String[] args)throws Exception{
        for(int i=0;i<32;i++)key[i]=(byte)i;
        byte[] request=negotiate();
        byte[] expected=ControlSession.pack(bytes("DMSDP/1.0 200 OK\r\nCSeq: 0\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\nPlatform: 0\r\n\r\n"),0);
        if(args.length>0){
            List<String> vectors=java.nio.file.Files.readAllLines(java.nio.file.Paths.get(args[0]));
            try(ControlSession c=new ControlSession(key,t->{})){
                check(hex(c.initial()).equals(vectors.get(0)));
                check(hex(c.feed(request,request.length).get(0)).equals(vectors.get(1)));
            }
        }
        for(int split=0;split<=request.length;split++)try(ControlSession c=new ControlSession(key,t->{})){
            List<byte[]> out=new ArrayList<>();out.addAll(c.feed(Arrays.copyOfRange(request,0,split),split));out.addAll(c.feed(Arrays.copyOfRange(request,split,request.length),request.length-split));
            check(c.negotiated()&&out.size()==1&&Arrays.equals(out.get(0),expected));
        }
        List<String> logs=new ArrayList<>();
        try(ControlSession c=new ControlSession(key,logs::add);SessionCipher peer=new SessionCipher(key,1498)){
            c.feed(request,request.length);
            byte[] heart=ControlSession.pack(peer.encrypt(bytes("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 123\r\n\r\n")),171);
            check(c.feed(heart,heart.length).isEmpty());
            byte[] ours=c.heartbeat();check(ours[0]==(byte)171&&new String(peer.decrypt(Arrays.copyOfRange(ours,3,ours.length)),StandardCharsets.US_ASCII).contains("CSeq: 2"));
            byte[] clock=ControlSession.pack(peer.encrypt(bytes("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\nTimeServerPort: 43210\r\nTimeUsHigh: 0\r\nTimeUsLow: -2147483648\r\n\r\n")),171);
            List<byte[]> clockOut=c.feed(clock,clock.length);check(clockOut.size()==1);
            byte[] ack=clockOut.get(0);check(ack[0]==(byte)171&&new String(peer.decrypt(Arrays.copyOfRange(ack,3,ack.length)),StandardCharsets.US_ASCII).contains("CSeq: 3"));
            byte[] body=bytes("private-device-example");
            byte[] binary=NearbyWire.join(new byte[]{36,0,0,0,3,-1,-1,-1,-1,0,(byte)body.length},body);
            byte[] frame=ControlSession.pack(peer.encrypt(binary),171);c.feed(frame,frame.length);
            check(!String.join("\n",logs).contains("private-device-example"));
            boolean plainRejected=false;try{c.feed(request,request.length);}catch(IOException e){plainRejected=true;}check(plainRejected);
        }
        bad(new byte[]{1,0,1,65});bad(new byte[]{0,0,0});bad(new byte[]{0,5,(byte)218});
        bad(ControlSession.pack(bytes("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 0\r\ncseq: 1\r\n\r\n"),0));
        bad(ControlSession.pack(bytes("SET_PARAMETER * DMSDP/1.0\r\nContent-Length: 99999\r\n\r\n"),0));
        bad(ControlSession.pack(new byte[17],171));
        try(ControlSession c=new ControlSession(key,t->{})){boolean rejected=false;try{c.heartbeat();}catch(IOException e){rejected=true;}check(rejected);}
        System.out.println("PASS: every negotiation split, AES-CTR heartbeat and binary metadata; duplicate/oversize/early-encrypted/plaintext-downgrade frames rejected; no body logging");
    }
    static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte n:data)b.append(String.format(Locale.ROOT,"%02x",n&255));return b.toString();}
}
