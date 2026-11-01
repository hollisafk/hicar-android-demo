package local.hicarlab;
import java.util.*;import java.nio.charset.StandardCharsets;import org.json.*;
public final class MicrophoneCapabilityTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static JSONObject json(byte[] packet)throws Exception{NearbyWire.Cursor c=new NearbyWire.Cursor(packet,11);c.uint();return AuthBusiness.object(new String(packet,c.p,packet.length-c.p,StandardCharsets.UTF_8));}
 public static void main(String[] args)throws Exception{
  for(boolean enabled:new boolean[]{false,true}){
   DeviceBusiness d=new DeviceBusiness("own","00048000","192.168.123.1","192.168.123.2",s->{},null,null,()->enabled);
   byte[] req=NearbyWire.join(NearbyWire.uint(101),"{\"deviceId\":\"peer\",\"businessId\":1}".getBytes(StandardCharsets.UTF_8));
   JSONObject own=json(d.receive(3,0xffffffffL,req).get(0));check(own.getJSONObject("properties").has("5002")==enabled);
   byte[] mask=NearbyWire.join(NearbyWire.uint(104),new byte[]{0,0,0,2});List<byte[]> services=d.receive(3,0xffffffffL,mask);
   check(services.size()==(enabled?1:0));if(enabled){JSONObject service=json(services.get(0));check(service.getString("serviceId").equals("mic1")&&service.getInt("serviceType")==2);}
   check(d.receive(3,0xffffffffL,mask).isEmpty());
  }
  System.out.println("PASS: microphone capability and service announced only when explicitly enabled/ready; no duplicate declaration");
 }
}
