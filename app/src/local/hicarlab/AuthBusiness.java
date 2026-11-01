package local.hicarlab;

// Adapted from CarLink auth_wire.py and hichain.py (MIT).
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

final class AuthBusiness implements AutoCloseable {
    interface Events {void log(String s);default void authenticated() {} default void authenticated(byte[] key) {authenticated();}}
    private final String self,pin,model;
    private final Events events;
    private long handle;
    private String identifiedPeer;
    private boolean identified,failed,done;
    private int depth,keyLength=32;
    private boolean quoted,escape;
    private final ByteArrayOutputStream partial=new ByteArrayOutputStream();
    AuthBusiness(String self,String pin,String model,Events e){this.self=self;this.pin=pin;this.model=model;events=e;}
    static JSONObject object(String s)throws JSONException {
        JSONTokener t=new JSONTokener(s);Object v=value(t,0);
        if(!(v instanceof JSONObject)||t.nextClean()!=0)throw new JSONException("expected object");return (JSONObject)v;
    }
    private static Object value(JSONTokener t,int d)throws JSONException {
        if(d>32)throw new JSONException("depth");char c=t.nextClean();t.back();
        if(c=='{'){
            t.nextClean();JSONObject o=new JSONObject();Set<String> keys=new HashSet<>();
            if(t.nextClean()=='}')return o;t.back();
            while(true){char q=t.nextClean();if(q!='"')throw new JSONException("key");String k=t.nextString(q);
                if(!keys.add(k)||t.nextClean()!=':')throw new JSONException("duplicate or invalid key");o.put(k,value(t,d+1));
                char end=t.nextClean();if(end=='}')return o;if(end!=',')throw new JSONException("object separator");}
        }
        if(c=='['){t.nextClean();JSONArray a=new JSONArray();if(t.nextClean()==']')return a;t.back();
            while(true){a.put(value(t,d+1));char end=t.nextClean();if(end==']')return a;if(end!=',')throw new JSONException("array separator");}}
        if(c=='"'){t.nextClean();return t.nextString('"');}
        Object v=t.nextValue();if(v instanceof String||v instanceof JSONObject||v instanceof JSONArray)throw new JSONException("invalid scalar");return v;
    }
    void feed(byte[] b,int n,OutputStream out)throws Exception {
        if(failed||done)throw new IOException("authentication session finished");
        try {
            for(int i=0;i<n;i++){
                int x=b[i]&255;if(partial.size()==0){if(x==32||x==10||x==13||x==9)continue;if(x!='{')throw new IOException("JSON marker");}
                partial.write(x);if(partial.size()>65536)throw new IOException("JSON limit");
                if(quoted){if(escape)escape=false;else if(x=='\\')escape=true;else if(x=='"')quoted=false;}
                else if(x=='"')quoted=true;else if(x=='{'||x=='['){if(++depth>32)throw new IOException("JSON nesting");}
                else if(x=='}'||x==']'){
                    if(--depth==0){
                        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(partial.toByteArray())).toString();
                        partial.reset();receive(object(text),out);if(done)return;
                    }
                }
            }
        }catch(Exception e){failed=true;throw e;}
    }
    private JSONObject envelope(int op,JSONObject data)throws JSONException {
        return new JSONObject().put("version","A_0").put("discover_type",1).put("oper_type",op).put("data",data.toString()).put("usb_connection",false);
    }
    private static void send(OutputStream out,JSONObject v)throws IOException {out.write(v.toString().getBytes(StandardCharsets.UTF_8));out.flush();}
    private static void normalize(JSONObject v)throws JSONException {
        JSONObject p=v.optJSONObject("payload");if(p==null)return;
        for(String k:new String[]{"peerUserType","keyLength"}){Object x=p.opt(k);if(x instanceof String&&((String)x).matches("0|1|16|32"))p.put(k,Integer.parseInt((String)x));}
    }
    private void receive(JSONObject outer,OutputStream out)throws Exception {
        Object op=outer.get("oper_type");if(!(op instanceof Integer))throw new IOException("operation type");
        Object text=outer.get("data");if(!(text instanceof String))throw new IOException("data type");JSONObject inner=object((String)text);
        if((Integer)op==1){
            if(identified||inner.optBoolean("usb_connection",false))throw new IOException("identity state");
            String peer=inner.getString("deviceId");int size=peer.getBytes(StandardCharsets.UTF_8).length;if(size<1||size>64)throw new IOException("identity length");
            identifiedPeer=peer;handle=NativeAuth.create(self,peer,pin,2);if(handle==0)throw new IOException("native session");identified=true;
            JSONObject details=new JSONObject().put("deviceId",self).put("devicename","HiCarLab").put("has_pin",true).put("usb_connection",false).put("CONNECT_ABILITY_KEY","1").put("CAR_MODE_ID",model);
            send(out,envelope(1,details));events.log("已交换实验身份，等待 HiChain 认证；请使用页面显示的六位连接码。");return;
        }
        if((Integer)op!=3||!identified)throw new IOException("authentication order");normalize(inner);
        int message=inner.getInt("message");
        if(message==32896){Object errorCode=inner.getJSONObject("payload").opt("errorCode");if(errorCode instanceof Integer)events.log("HiChain 对端报告错误码："+errorCode+"（仅记录数值）。");}
        if(message==17){
            JSONObject payload=inner.getJSONObject("payload");Object authId=payload.opt("peerAuthId");
            if(authId instanceof String&&((String)authId).matches("(?i)[0-9a-f]{2,128}")&&((String)authId).length()%2==0){
                String hex=(String)authId;byte[] decoded=new byte[hex.length()/2];for(int i=0;i<decoded.length;i++)decoded[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
                events.log("STS 请求身份检查：长度 "+decoded.length+" 字节，与已交换对端身份一致 "+Arrays.equals(decoded,identifiedPeer.getBytes(StandardCharsets.UTF_8))+"，与本机身份一致 "+Arrays.equals(decoded,self.getBytes(StandardCharsets.UTF_8))+"；未记录身份内容。");Arrays.fill(decoded,(byte)0);
            }else events.log("STS 请求未携带可比较的身份字段；未记录内容。");
            keyLength=inner.getJSONObject("payload").getInt("keyLength");if(keyLength!=16&&keyLength!=32)throw new IOException("key length");}
        byte[][] packets=NativeAuth.step(handle,inner.toString().getBytes(StandardCharsets.UTF_8),keyLength);
        if(packets==null||packets.length<1||packets[0].length!=16)throw new IOException("native response");
        IntBuffer meta=ByteBuffer.wrap(packets[0]).order(ByteOrder.nativeOrder()).asIntBuffer();
        int code=meta.get(0),result=meta.get(1),keyBytes=meta.get(2),error=meta.get(3);
        events.log("HiChain 消息 "+message+"：返回 "+code+"，状态 "+result+"，响应 "+(packets.length-1)+" 条。");
        if(error!=0||result==1)throw new IOException("authentication rejected");
        for(int i=1;i<packets.length;i++){JSONObject reply=object(new String(packets[i],StandardCharsets.UTF_8));if(message==17)events.log("STS 后端响应消息类型："+reply.getInt("message")+"。");normalize(reply);send(out,envelope(3,reply));}
        if(result==0){if(keyBytes!=16&&keyBytes!=32)throw new IOException("key not ready");byte[] key=NativeAuth.sessionKey(handle);if(key==null||key.length!=keyBytes)throw new IOException("session key unavailable");
            try{events.authenticated(key);done=true;}finally{Arrays.fill(key,(byte)0);}events.log("HiChain 后端报告认证成功；等待后续网络及媒体会话，认证成功本身不代表投屏成功。");}
    }
    boolean done(){return done;}
    @Override public void close(){if(handle!=0){NativeAuth.close(handle);handle=0;}}
}
