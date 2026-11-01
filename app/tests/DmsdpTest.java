package local.hicarlab;
import java.util.*;
import java.nio.file.*;
public class DmsdpTest {
 static byte[] hex(String x){byte[]b=new byte[x.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(x.substring(i*2,i*2+2),16);return b;}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[]a)throws Exception{
  String[] v=Files.readAllLines(Paths.get(a[0])).get(0).split(" ");byte[] request=hex(v[0]),reply=hex(v[1]);
  for(int split=0;split<=request.length;split++){
   DmsdpWire.Decoder d=new DmsdpWire.Decoder();List<DmsdpWire.Packet> frames=new ArrayList<>();frames.addAll(d.feed(Arrays.copyOfRange(request,0,split),split));frames.addAll(d.feed(Arrays.copyOfRange(request,split,request.length),request.length-split));check(frames.size()==1);check(Arrays.equals(DmsdpWire.controlOffer(frames.get(0),4007),reply));
  }
  byte[] bad=request.clone();bad[bad.length-1]^=1;boolean rejected=false;try{new DmsdpWire.Decoder().feed(bad,bad.length);}catch(Exception e){rejected=true;}check(rejected);
  System.out.println("DMSDP fragmented negotiation and offered endpoint match Python; bad CRC rejected");
 }
}
