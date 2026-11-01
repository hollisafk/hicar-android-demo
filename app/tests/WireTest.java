package local.hicarlab;
import java.nio.file.*;
import java.util.*;
public class WireTest {
 static byte[] hex(String x){byte[] b=new byte[x.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(x.substring(i*2,i*2+2),16);return b;}
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  int total=0;
  for(String line:Files.readAllLines(Paths.get(args[0]))){String[] v=line.split(" ",-1);int kind=Integer.parseInt(v[0]);byte[] payload=hex(v[1]),expected=hex(v[2]);check(Arrays.equals(NearbyWire.pack(kind,payload),expected));
   for(int split=0;split<=expected.length;split++){NearbyWire.Decoder d=new NearbyWire.Decoder();List<NearbyWire.Frame> frames=new ArrayList<>();frames.addAll(d.feed(Arrays.copyOfRange(expected,0,split),split));frames.addAll(d.feed(Arrays.copyOfRange(expected,split,expected.length),expected.length-split));check(frames.size()==1);check(frames.get(0).kind==kind);check(Arrays.equals(frames.get(0).payload,payload));total++;}
   byte[] bad=expected.clone();bad[bad.length-1]^=1;boolean rejected=false;try{new NearbyWire.Decoder().feed(bad,bad.length);}catch(Exception e){rejected=true;}check(rejected);
  }
  NearbyWire.Negotiator n=new NearbyWire.Negotiator(new byte[]{'a'},4);
  byte[] request=NearbyWire.join(NearbyWire.tlv(1,new byte[]{2}),NearbyWire.tlv(3,new byte[]{'b'}),NearbyWire.tlv(5,new byte[]{1}));
  check(n.respond(new NearbyWire.Frame(0,0,request))!=null);check(n.seen==7);
  byte[] business=NearbyWire.join(NearbyWire.uint(2),NearbyWire.uint(19),NearbyWire.uint(0),NearbyWire.uint(0));
  byte[] reply=n.respond(new NearbyWire.Frame(2,0,NearbyWire.tlv(3,business)));
  Files.write(Paths.get(args[1]),reply);
  boolean rejected=false;try{n.respond(new NearbyWire.Frame(0,1,new byte[0]));}catch(Exception e){rejected=true;}check(rejected);
  System.out.println("wire: "+total+" fragmented decode cases and CRC rejection passed");
 }
}
