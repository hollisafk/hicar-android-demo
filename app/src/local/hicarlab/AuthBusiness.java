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
    private boolean identified,failed,done; private boolean rebindHinted;
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
    // 只记录字段名与长度/简单数值，不记录身份、密钥或认证包内容。
    private static String shape(JSONObject o){
        StringBuilder sb=new StringBuilder();java.util.Iterator<String> it=o.keys();
        while(it.hasNext()){
            String k=it.next();Object v=o.opt(k);
            String t=v instanceof JSONObject?"obj{"+((JSONObject)v).length()+"}":
                     v instanceof JSONArray?"arr:"+((JSONArray)v).length():
                     v instanceof String?"str:"+((String)v).length():
                     v instanceof Number?"num:"+v:"other";
            if(sb.length()>0)sb.append(',');sb.append(k).append('=').append(t);
        }
        return sb.toString();
    }
    private void logKeyStore(){
        File d=new File("/data/user/0/local.hicarlab/app_huks");File[] fs=d.listFiles();
        if(fs==null){events.log("HUKS 目录不可列。");return;}
        StringBuilder sb=new StringBuilder();
        for(File f:fs){if(sb.length()>0)sb.append(',');sb.append(f.getName()).append(':').append(f.length());}
        events.log("HUKS 密钥库文件 ["+sb+"]，共 "+fs.length+" 个；self 长度="+self.length()+"。");
    }
    private void receive(JSONObject outer,OutputStream out)throws Exception {
        Object op=outer.get("oper_type");if(!(op instanceof Integer))throw new IOException("operation type");
        Object text=outer.get("data");if(!(text instanceof String))throw new IOException("data type");JSONObject inner=object((String)text);
        if((Integer)op==1){
            if(identified||inner.optBoolean("usb_connection",false))throw new IOException("identity state");
            String peer=inner.getString("deviceId");int size=peer.getBytes(StandardCharsets.UTF_8).length;if(size<1||size>64)throw new IOException("identity length");
            // 只记录字段名与布尔模式标志，不记录身份、PIN 或认证包内容。
            StringBuilder fields=new StringBuilder();java.util.Iterator<String> names=inner.keys();
            while(names.hasNext()){if(fields.length()>0)fields.append(',');fields.append(names.next());}
            boolean peerHasPin=inner.optBoolean("has_pin",true);
            int autoState=inner.optInt("auto_connect_state",-1);
            // 车机签名固定用 KEY_ALIAS_ACCESSOR_PK（CMake 未开 _SUPPORT_SEC_CLONE_）。
            // 手机验签时 peer_user_type=ACCESSORY 才会用同一别名，因此两端都保持
            // HC_ACCESSORY(2)；改成 HC_CENTRE(1) 会让手机改用 KEY_ALIAS_LT_KEY_PAIR，
            // 别名不匹配导致 errorCode=1。
            int role=2;
            events.log("身份交换模式：字段 ["+fields+"]，has_pin="+peerHasPin+"，auto_connect_state="+autoState+"，字段数="+inner.length()+"，本端角色="+role+"。");
            identifiedPeer=peer;handle=NativeAuth.create(self,peer,pin,role);if(handle==0)throw new IOException("native session");identified=true;
            JSONObject details=new JSONObject().put("deviceId",self).put("devicename","HiCarLab").put("has_pin",true).put("usb_connection",false).put("CONNECT_ABILITY_KEY","1").put("CAR_MODE_ID",model);
            send(out,envelope(1,details));events.log("已交换实验身份，等待 HiChain 认证；请使用页面显示的六位连接码。");return;
        }
        if((Integer)op!=3||!identified)throw new IOException("authentication order");normalize(inner);
        int message=inner.getInt("message");
        if(message==32896){Object errorCode=inner.getJSONObject("payload").opt("errorCode");if(errorCode instanceof Integer)events.log("HiChain 对端报告错误码："+errorCode+"（仅记录数值）。");}
        if(message==17){
            rebindHinted=true;
            events.log("收到 AUTH_START_REQUEST（0x0011）：手机端按已配对设备发起免输码重连；照常进入 HiChain STS 免密流程（17→18→0）。");
            JSONObject payload=inner.getJSONObject("payload");
            events.log("STS 请求外层结构："+shape(inner));
            events.log("STS 请求 payload 结构："+shape(payload));
            events.log("STS 请求协议字段：version="+payload.opt("version")+"，peerUserType="+payload.opt("peerUserType")+"，keyLength="+payload.opt("keyLength")+"，operationCode="+payload.opt("operationCode")+"，support256mod="+payload.opt("support256mod")+"。");
            logKeyStore();
            Object authId=payload.opt("peerAuthId");
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
        for(int i=1;i<packets.length;i++){
            JSONObject reply=object(new String(packets[i],StandardCharsets.UTF_8));
            if(message==17){
                events.log("STS 后端响应消息类型："+reply.optInt("message",-1)+"；外层结构："+shape(reply));
                JSONObject rp=reply.optJSONObject("payload");
                if(rp!=null){events.log("STS 响应 payload 结构："+shape(rp));
                    events.log("STS 响应协议字段：version="+rp.opt("version")+"，peerUserType="+rp.opt("peerUserType")+"，peerAuthId 长度="+rp.optString("peerAuthId").length()+"。");
                    // 曾在 Java 层把 version 对齐到手机声明的 2.0.31，实测仍返回
                    // errorCode=1，已证伪：失败点在 authData 解密/验签，而非版本字段。
                    // 保留原响应不做改写，避免引入未验证的协议偏差。
                    }
            }
            normalize(reply);send(out,envelope(3,reply));
        }
        if(result==0){if(keyBytes!=16&&keyBytes!=32)throw new IOException("key not ready");byte[] key=NativeAuth.sessionKey(handle);if(key==null||key.length!=keyBytes)throw new IOException("session key unavailable");
            try{events.authenticated(key);done=true;}finally{Arrays.fill(key,(byte)0);}events.log("HiChain 后端报告认证成功；等待后续网络及媒体会话，认证成功本身不代表投屏成功。");}
    }
    boolean done(){return done;}
    boolean wantsRebindHint(){return rebindHinted;}
    @Override public void close(){if(handle!=0){NativeAuth.close(handle);handle=0;}}
}
