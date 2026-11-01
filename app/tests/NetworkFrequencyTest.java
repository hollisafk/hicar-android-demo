package local.hicarlab;
import java.util.*;
public final class NetworkFrequencyTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  check(NetworkResource.frequencyMask(5220)==4&&NetworkResource.frequencyMask(5745)==16&&NetworkResource.frequencyMask(2412)==0);
  NetworkResource.Offer offer=new NetworkResource.Offer("LAB","02:11:22:33:44:66","test-only-pass","192.168.50.1","192.168.50.2",5220,4005);
  for(int mask:new int[]{4,16}){
   NetworkResource r=new NetworkResource(offer);r.authenticated=true;
   byte[] caps=NearbyWire.join(NearbyWire.uint(2),NearbyWire.uint(1),NearbyWire.uint(4),NearbyWire.uint(1),NearbyWire.uint(5),NearbyWire.uint(mask));
   byte[] payload=NearbyWire.tlv(1,NearbyWire.join(NearbyWire.uint(3),NearbyWire.tlv(3,caps)));
   check(r.respond(new NearbyWire.Frame(4,0,payload))!=null);check(r.negotiated==(mask==4));
  }
  System.out.println("PASS: actual channel44 frequency accepted only with corresponding peer capability; channel149 vectors remain unchanged");
 }
}
