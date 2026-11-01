package local.hicarlab;
import java.util.*;
import java.nio.file.*;
public class NetworkTest {
 static byte[] hex(String x){if(x.equals("-"))return null;byte[]b=new byte[x.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(x.substring(i*2,i*2+2),16);return b;}
 static void check(boolean x){if(!x)throw new AssertionError();}
 public static void main(String[]args)throws Exception{
  NetworkResource.Offer offer=new NetworkResource.Offer("LAB","02:11:22:33:44:66","not-a-real-password","192.168.49.1","192.168.49.2",5745,4005);
  NetworkResource r=new NetworkResource(offer);r.dataPort=4006;check(r.respond(new NearbyWire.Frame(4,0,new byte[0]))==null);r.authenticated=true;int cases=0;
  for(String line:Files.readAllLines(Paths.get(args[0]))){String[]v=line.split(" ");int kind=Integer.parseInt(v[0]);byte[]expected=hex(v[2]);
   if(kind==-1){List<byte[]>p=r.takePending();check(p.size()==1);check(Arrays.equals(p.get(0),expected));}
   else check(Arrays.equals(r.respond(new NearbyWire.Frame(kind,0,hex(v[1]))),expected));cases++;
  }
  check(r.negotiated&&r.credentialsSent);check(r.respond(new NearbyWire.Frame(4,1,new byte[0]))==null);
  boolean duplicate=false;try{NetworkResource.abilities(NearbyWire.join(NearbyWire.uint(2),NearbyWire.uint(1),NearbyWire.uint(2),NearbyWire.uint(1)));}catch(Exception e){duplicate=true;}check(duplicate);
  for(String mac:new String[]{"02:00:00:00:00:00","00:00:00:00:00:00","invalid"}){boolean rejected=false;try{new NetworkResource.Offer("LAB",mac,"not-a-real-password","192.168.49.1","192.168.49.2",5745,4005);}catch(Exception e){rejected=true;}check(rejected);}
  check(new String(NetworkResource.Offer.ssidOnly("LAB","not-a-real-password","192.168.49.1","192.168.49.2",5745,4005).credentials(),java.nio.charset.StandardCharsets.US_ASCII).contains("LAB\n\nnot-a-real-password\n5745"));
  System.out.println(cases+" network negotiation packets match upstream Python; unauthenticated/encrypted gating, masked BSSID and duplicate capabilities rejected");
 }
}
