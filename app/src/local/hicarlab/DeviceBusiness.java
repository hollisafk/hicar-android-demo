package local.hicarlab;

// CarLink module 3 command 101/104 semantics (MIT), with no media declaration.
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.json.*;

final class DeviceBusiness implements ControlSession.Business {
    interface Display {boolean ready();void endpoint(int port)throws IOException;}
    interface Speaker {boolean ready();}
    private final Speaker speaker,microphone;private boolean speakerAnnounced,microphoneAnnounced;
    private final Display display;
    private boolean announced;
    private final String id,model,owner,client;
    private final ControlSession.Events events;
    private String peer;
    private boolean exchanged;
    DeviceBusiness(String id,String model,String owner,String client,ControlSession.Events e){this(id,model,owner,client,e,null);}
    DeviceBusiness(String id,String model,String owner,String client,ControlSession.Events e,Display d){this(id,model,owner,client,e,d,null);}
    DeviceBusiness(String id,String model,String owner,String client,ControlSession.Events e,Display d,Speaker a){this(id,model,owner,client,e,d,a,null);}
    DeviceBusiness(String id,String model,String owner,String client,ControlSession.Events e,Display d,Speaker a,Speaker m){this.id=id;this.model=model;this.owner=owner;this.client=client;events=e;display=d;speaker=a;microphone=m;}
    static byte[] packet(byte[] body)throws IOException{
        if(body.length>1400)throw new IOException("device reply limit");
        return NearbyWire.join(new byte[]{36,0,0,0,3,-1,-1,-1,-1,(byte)(body.length>>>8),(byte)body.length},body);
    }
    public List<byte[]> receive(long module,long type,byte[] body)throws IOException{
        if(module==5&&type==0xffffffffL){
            try{NearbyWire.Cursor c=new NearbyWire.Cursor(body,0);int command=c.uint(),port=c.uint();if(command!=101||c.p!=body.length||port<1||port>65535||!announced||display==null)throw new IOException("unexpected media endpoint");display.endpoint(port);return Collections.emptyList();}
            catch(NearbyWire.Short e){throw new IOException("truncated media endpoint");}
        }
        if(module!=3||type!=0xffffffffL)return Collections.emptyList();
        if(body.length<1||body.length>4096)throw new IOException("device business limit");
        try{
            NearbyWire.Cursor cursor=new NearbyWire.Cursor(body,0);int command=cursor.uint();
            if(command==101){
                String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body,cursor.p,body.length-cursor.p)).toString();
                JSONObject incoming=AuthBusiness.object(json);
                Object identity=incoming.get("deviceId");
                if(!(identity instanceof String)||((String)identity).isEmpty()||((String)identity).getBytes(StandardCharsets.UTF_8).length>256)throw new IOException("device identity field");
                if(peer!=null&&!peer.equals(identity))throw new IOException("device identity changed");
                Object bid=incoming.has("businessId")?incoming.get("businessId"):Integer.valueOf(1);
                if(!(bid instanceof Integer)||(Integer)bid<0)throw new IOException("device business id");
                if(exchanged){events.log("DMSDP 重复设备信息请求已忽略；未记录对端身份。");return Collections.emptyList();}
                // Only known local identity, current network and experiment model.
                // Unavailable local Bluetooth MAC is omitted, never fabricated.
                JSONObject own=new JSONObject().put("deviceId",id).put("deviceName","HiCarLab").put("deviceType",4)
                    .put("btName","HiCarLab").put("localIp",owner).put("remoteIp",client).put("port",0)
                    .put("properties",new JSONObject().put("2003",model)).put("businessId",bid);
                if(microphone!=null&&microphone.ready())own.getJSONObject("properties").put("5002",true);
                byte[] reply=packet(NearbyWire.join(NearbyWire.uint(101),own.toString().getBytes(StandardCharsets.UTF_8)));
                peer=(String)identity;exchanged=true;
                events.log("DMSDP 设备信息请求已处理，发送本机身份及本轮网络参数；尚待手机确认。");
                return Collections.singletonList(reply);
            }
            if(command==104){
                if(!exchanged||body.length-cursor.p!=4)throw new IOException("service request order");
                long mask=0;for(int i=cursor.p;i<body.length;i++)mask=(mask<<8)|(body[i]&255);
                events.log("DMSDP 手机请求服务，能力掩码 "+mask+"。");
                List<byte[]> announcements=new ArrayList<>();
                if((mask&8)!=0&&display!=null&&display.ready()&&!announced){
                    JSONObject service=new JSONObject().put("deviceId",id).put("deviceName","HiCarLab").put("serviceId","display1").put("serviceType",8)
                        .put("properties",new JSONObject().put("4001",1.0).put("1009",VideoSpec.WIDTH).put("1010",VideoSpec.HEIGHT)).put("status",new JSONObject());
                    byte[] reply=packet(NearbyWire.join(NearbyWire.uint(102),service.toString().getBytes(StandardCharsets.UTF_8)));announced=true;
                    events.log("H.264 解码器和窗口就绪，已宣布 "+VideoSpec.WIDTH+"×"+VideoSpec.HEIGHT+" 显示服务；尚未收到视频。");announcements.add(reply);
                }
                if((mask&4)!=0&&speaker!=null&&speaker.ready()&&!speakerAnnounced){
                    JSONObject service=new JSONObject().put("deviceId",id).put("deviceName","HiCarLab").put("serviceId","speaker1").put("serviceType",4)
                        .put("properties",new JSONObject().put("2003","48000,44100").put("2004","1").put("2005","12")).put("status",new JSONObject());
                    announcements.add(packet(NearbyWire.join(NearbyWire.uint(102),service.toString().getBytes(StandardCharsets.UTF_8))));speakerAnnounced=true;
                    events.log("已宣布 AAC 扬声器服务；实际解码与出声待验证。");
                }
                if((mask&2)!=0&&microphone!=null&&microphone.ready()&&!microphoneAnnounced){
                    JSONObject service=new JSONObject().put("deviceId",id).put("deviceName","HiCarLab").put("serviceId","mic1").put("serviceType",2)
                        .put("properties",new JSONObject().put("2003","48000").put("2004","1").put("2005","12")).put("status",new JSONObject());
                    announcements.add(packet(NearbyWire.join(NearbyWire.uint(102),service.toString().getBytes(StandardCharsets.UTF_8))));microphoneAnnounced=true;
                    events.log("已宣布麦克风实验服务；等待手机语音请求，尚未录音。");
                }
                return announcements;
            }else events.log("DMSDP 设备业务命令 "+command+"，载荷 "+(body.length-cursor.p)+" 字节；未记录内容。");
            return Collections.emptyList();
        }catch(NearbyWire.Short|JSONException|CharacterCodingException e){throw new IOException("invalid device business");}
    }
}
