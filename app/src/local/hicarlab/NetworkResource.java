package local.hicarlab;

// Port of CarLink network_wire.py (MIT). Only advertise a verified, ready AP.
import java.io.*;
import java.nio.charset.*;
import java.util.*;

final class NetworkResource {
    static int frequencyMask(int frequency){int[] channels={5180,5200,5220,5240,5745,5765,5785,5805,5825};for(int i=0;i<channels.length;i++)if(channels[i]==frequency)return 1<<i;return 0;}
    static final class Offer {
        private final String ssid,bssid,password,owner,client;
        final int frequency,controlPort;
        Offer(String ssid,String bssid,String password,String owner,String client,int frequency,int controlPort) {
            this(ssid,bssid,password,owner,client,frequency,controlPort,false);
        }
        static Offer ssidOnly(String ssid,String password,String owner,String client,int frequency,int controlPort) {
            return new Offer(ssid,"",password,owner,client,frequency,controlPort,true);
        }
        private Offer(String ssid,String bssid,String password,String owner,String client,int frequency,int controlPort,boolean absentBssid) {
            boolean addressOkay=bssid!=null&&bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&!bssid.equalsIgnoreCase("02:00:00:00:00:00")&&!bssid.equals("00:00:00:00:00:00");
            if(!text(ssid,1,32)||!text(password,8,63)||!(addressOkay||absentBssid&&"".equals(bssid))||frequencyMask(frequency)==0||controlPort<1024||controlPort>65535)
                throw new IllegalArgumentException("unverified AP offer");
            int[] a=ipv4(owner),b=ipv4(client);
            if(a[0]!=b[0]||a[1]!=b[1]||a[2]!=b[2]||a[3]==b[3]||a[3]==0||a[3]==255||b[3]==0||b[3]==255)
                throw new IllegalArgumentException("AP network addresses");
            if(!(a[0]==10||a[0]==192&&a[1]==168||a[0]==172&&a[1]>=16&&a[1]<=31))throw new IllegalArgumentException("private AP network required");
            this.ssid=ssid;this.bssid=bssid;this.password=password;this.owner=owner;this.client=client;this.frequency=frequency;this.controlPort=controlPort;
        }
        private static boolean text(String s,int min,int max) {
            if(s==null||s.length()<min||s.length()>max)return false;
            for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c<32||c>126||c=='#')return false;}return true;
        }
        private static int[] ipv4(String s){
            if(s==null)throw new IllegalArgumentException("IPv4");String[] parts=s.split("\\.",-1);if(parts.length!=4)throw new IllegalArgumentException("IPv4");int[] a=new int[4];
            for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException("IPv4");a[i]=Integer.parseInt(parts[i]);if(a[i]>255)throw new IllegalArgumentException("IPv4");}return a;
        }
        String ownerIp(){return owner;}
        String clientIp(){return client;}
        byte[] credentials() {return (client+"##"+ssid+"\n"+bssid+"\n"+password+"\n"+frequency+"\n1##version:3##"+controlPort+"##2##hicarApIp:"+owner).getBytes(StandardCharsets.US_ASCII);}
    }
    final Offer offer;
    volatile boolean authenticated,negotiated,credentialsSent;
    int dataPort;
    private final List<byte[]> pending=new ArrayList<>();
    NetworkResource(Offer offer){this.offer=offer;}
    static Map<Integer,Integer> abilities(byte[] data)throws IOException {
        if(data.length>256)throw new IOException("capability limit");Map<Integer,Integer> values=new LinkedHashMap<>();NearbyWire.Cursor c=new NearbyWire.Cursor(data,0);
        try{while(c.p<data.length){int key=c.uint(),value=c.uint();if(values.put(key,value)!=null||values.size()>32)throw new IOException("capability fields");}}
        catch(NearbyWire.Short e){throw new IOException("capability truncated");}return values;
    }
    private static int cap(Map<Integer,Integer> a,int key){Integer n=a.get(key);return n==null?0:n;}
    List<byte[]> takePending(){List<byte[]> result=new ArrayList<>(pending);pending.clear();return result;}
    byte[] respond(NearbyWire.Frame frame)throws IOException {
        if(!authenticated||(frame.flags&1)!=0)return null;
        ByteArrayOutputStream replies=new ByteArrayOutputStream();
        try{
            for(NearbyWire.Field f:NearbyWire.fields(frame.payload)){
                byte[] reply=null;NearbyWire.Cursor c=new NearbyWire.Cursor(f.value,0);
                if(frame.kind==4&&f.tag==1){
                    int channel=c.uint();List<NearbyWire.Field> detail=NearbyWire.fields(Arrays.copyOfRange(f.value,c.p,f.value.length));
                    if(channel!=3||detail.size()!=1||detail.get(0).tag!=3)throw new IOException("network negotiation");
                    Map<Integer,Integer> caps=abilities(detail.get(0).value);
                    boolean accepted=cap(caps,2)==1&&cap(caps,4)==1&&(cap(caps,5)&frequencyMask(offer.frequency))!=0;
                    byte[] result=NearbyWire.join(NearbyWire.uint(3),NearbyWire.uint(accepted?1:255));
                    if(accepted)result=NearbyWire.join(result,NearbyWire.tlv(3,NearbyWire.join(new byte[]{1},NearbyWire.uint(offer.frequency))));
                    reply=NearbyWire.tlv(2,result);negotiated=accepted;
                }else if(frame.kind==4&&f.tag==3){
                    if(!negotiated||dataPort<1024||dataPort>65535)throw new IOException("data endpoint not ready");
                    int channel=c.uint(),business=c.uint(),size=c.uint();if(size>128)throw new IOException("data tag limit");c.take(size);int protocol=c.uint();
                    if(channel!=3||business!=20||protocol!=1||c.p!=f.value.length)throw new IOException("data socket request");
                    reply=NearbyWire.tlv(4,NearbyWire.join(NearbyWire.uint(0),NearbyWire.uint(dataPort),f.value));
                }else if(frame.kind==4&&f.tag==7){
                    int channel=c.uint(),status=c.uint();List<NearbyWire.Field> selected=NearbyWire.fields(Arrays.copyOfRange(f.value,c.p,f.value.length));
                    if(!negotiated||channel!=3||status!=1||selected.size()!=1||selected.get(0).tag!=3)throw new IOException("network selection");
                    byte[] role=selected.get(0).value;if(role.length<2||role[0]!=2)throw new IOException("network role");
                    NearbyWire.Cursor r=new NearbyWire.Cursor(role,1);if(r.uint()!=offer.frequency||r.p!=role.length)throw new IOException("frequency");
                    if(pending.size()>=4)throw new IOException("network command limit");pending.add(NearbyWire.pack(33,NearbyWire.tlv(1,new byte[0])));
                }else if(frame.kind==33&&(f.tag==1||f.tag==2||f.tag==4)){
                    if(!negotiated)throw new IOException("network command order");
                    if(f.tag==1)reply=NearbyWire.tlv(2,"busy".getBytes(StandardCharsets.US_ASCII));
                    else{
                        String mac=new String(f.value,StandardCharsets.US_ASCII);if(!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))throw new IOException("client address");
                        reply=NearbyWire.tlv(3,offer.credentials());credentialsSent=true;
                    }
                }else if(frame.kind==33&&f.tag==5)reply=NearbyWire.tlv(6,new byte[0]);
                if(reply!=null)replies.write(reply,0,reply.length);
            }
        }catch(NearbyWire.Short e){throw new IOException("network frame truncated");}
        return replies.size()==0?null:NearbyWire.pack(frame.kind,replies.toByteArray());
    }
}
