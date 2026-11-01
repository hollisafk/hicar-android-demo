package local.hicarlab;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class DeviceBusinessTest {
    static byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static byte[] request(String s){return NearbyWire.join(NearbyWire.uint(101),b(s));}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static DeviceBusiness fresh(List<String> log){return new DeviceBusiness("own-private-id","00048000","192.168.123.1","192.168.123.2",log::add);}
    static void rejected(DeviceBusiness d,byte[] data)throws Exception{try{d.receive(3,0xffffffffL,data);throw new AssertionError("expected rejection");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception{
        List<String> logs=new ArrayList<>();DeviceBusiness device=fresh(logs);
        byte[] req=request("{\"deviceId\":\"peer-private-example\",\"businessId\":19,\"unknownSecret\":\"do-not-echo\"}");
        byte[] reply=device.receive(3,0xffffffffL,req).get(0);
        check(reply[0]==36&&reply[4]==3&&reply[5]==(byte)255);
        NearbyWire.Cursor cursor=new NearbyWire.Cursor(reply,11);check(cursor.uint()==101);
        JSONObject own=AuthBusiness.object(new String(reply,cursor.p,reply.length-cursor.p,StandardCharsets.UTF_8));
        check(own.getString("deviceId").equals("own-private-id")&&own.getInt("businessId")==19&&own.getInt("deviceType")==4);
        check(own.getString("localIp").equals("192.168.123.1")&&!own.has("bluetoothMac")&&!own.has("unknownSecret"));
        check(own.getJSONObject("properties").length()==1&&own.getJSONObject("properties").getString("2003").equals("00048000"));
        check(device.receive(3,0xffffffffL,req).isEmpty());
        byte[] service=NearbyWire.join(NearbyWire.uint(104),new byte[]{0,0,3,63});
        check(device.receive(3,0xffffffffL,service).isEmpty());
        check(logs.stream().anyMatch(s->s.contains("831")));
        check(!String.join("\n",logs).contains("private")&&!String.join("\n",logs).contains("do-not-echo"));
        rejected(fresh(logs),service);
        for(String invalid:new String[]{"{\"deviceId\":\"a\",\"deviceId\":\"b\"}","{\"deviceId\":\"\"}","{\"deviceId\":2}","{\"deviceId\":\"a\",\"businessId\":\"1\"}"})rejected(fresh(logs),request(invalid));
        rejected(device,request("{\"deviceId\":\"changed\"}"));
        rejected(fresh(logs),NearbyWire.join(NearbyWire.uint(101),new byte[]{(byte)255}));
        // Real encrypted transport through ControlSession, fragmented at every boundary.
        byte[] key=new byte[32];
        for(int split=0;split<=req.length+30;split++)try(SessionCipher peer=new SessionCipher(key,1498);ControlSession control=new ControlSession(key,logs::add,fresh(logs))){
            byte[] negotiation=ControlSession.pack(b("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 0\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\n\r\n"),0);control.feed(negotiation,negotiation.length);
            byte[] frame=ControlSession.pack(peer.encrypt(DeviceBusiness.packet(req)),171);
            int cut=split;List<byte[]> result=new ArrayList<>();result.addAll(control.feed(Arrays.copyOfRange(frame,0,cut),cut));result.addAll(control.feed(Arrays.copyOfRange(frame,cut,frame.length),frame.length-cut));
            check(result.size()==1&&result.get(0)[0]==(byte)171);
            byte[] plaintext=peer.decrypt(Arrays.copyOfRange(result.get(0),3,result.get(0).length));check(plaintext[0]==36);
        }
        System.out.println("PASS: encrypted device-information response, local fields only, service-mask observation, duplicate/identity/JSON/order validation; no media announcement or private body logging");
    }
}
