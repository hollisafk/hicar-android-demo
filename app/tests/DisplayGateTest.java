package local.hicarlab;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.json.*;
public final class DisplayGateTest {
    static void check(boolean x){if(!x)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        final boolean[] ready={false};final int[] endpoint={0};
        DeviceBusiness d=new DeviceBusiness("own","00048000","192.168.49.1","192.168.49.2",t->{},new DeviceBusiness.Display(){public boolean ready(){return ready[0];}public void endpoint(int p){endpoint[0]=p;}});
        byte[] device=NearbyWire.join(NearbyWire.uint(101),"{\"deviceId\":\"peer\",\"businessId\":1}".getBytes(StandardCharsets.UTF_8));
        byte[] mask=NearbyWire.join(NearbyWire.uint(104),new byte[]{0,0,3,63});
        byte[] port=NearbyWire.join(NearbyWire.uint(101),NearbyWire.uint(49000));
        boolean rejected=false;try{d.receive(5,0xffffffffL,port);}catch(IOException e){rejected=true;}check(rejected&&endpoint[0]==0);
        d.receive(3,0xffffffffL,device);check(d.receive(3,0xffffffffL,mask).isEmpty());
        ready[0]=true;List<byte[]> result=d.receive(3,0xffffffffL,mask);check(result.size()==1);
        byte[] response=result.get(0);NearbyWire.Cursor c=new NearbyWire.Cursor(response,11);check(c.uint()==102);
        JSONObject service=AuthBusiness.object(new String(response,c.p,response.length-c.p,StandardCharsets.UTF_8));
        check(service.getInt("serviceType")==8&&service.getString("serviceId").equals("display1")&&service.getJSONObject("properties").getInt("1009")==800&&service.getJSONObject("properties").getInt("1010")==480);
        check(d.receive(3,0xffffffffL,mask).isEmpty());d.receive(5,0xffffffffL,port);check(endpoint[0]==49000);
        System.out.println("PASS: no display before decoder/window readiness, no media endpoint before announcement, one display declaration, validated endpoint callback; no audio service");
    }
}
