package local.hicarlab;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fake authenticated phone: both transports must connect before video starts. */
public final class HiSightTcpTest {
    static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static void write(OutputStream out,byte[] payload)throws Exception{DataOutputStream d=new DataOutputStream(out);d.writeInt(payload.length);d.write(payload);d.flush();}
    static byte[] read(InputStream in)throws Exception{DataInputStream d=new DataInputStream(in);int n=d.readInt();check(n>0&&n<=65000);byte[] data=new byte[n];d.readFully(data);return data;}
    public static void main(String[] args)throws Exception{
        byte[] key=new byte[32];for(int i=0;i<key.length;i++)key[i]=(byte)i;
        AtomicBoolean active=new AtomicBoolean(true);AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch frame=new CountDownLatch(1);AtomicInteger delivered=new AtomicInteger();
        byte[] elementary=new byte[]{0,0,0,1,103,12,34,56};
        Set<Socket> sockets=Collections.synchronizedSet(new HashSet<>());List<String> logs=Collections.synchronizedList(new ArrayList<>());
        InputRouter router=new InputRouter();
        HiSightClient client=new HiSightClient("127.0.0.1","127.0.0.1",key,new HiSightClient.Video(){
            public boolean ready(){return true;}
            public void offer(int kind,long time,byte[] data){if(kind!=96||!Arrays.equals(data,elementary))failure.set(new AssertionError("ES mismatch"));delivered.incrementAndGet();frame.countDown();}
        },new HiSightClient.Events(){public boolean active(){return active.get();}public void log(String s){logs.add(s);}public void add(Socket s){sockets.add(s);}public void remove(Socket s){sockets.remove(s);}},router);
        Thread worker=null;
        try(ServerSocket rtsp=new ServerSocket(0,1,InetAddress.getLoopbackAddress());ServerSocket media=new ServerSocket(0,1,InetAddress.getLoopbackAddress());ServerSocket input=new ServerSocket(0,1,InetAddress.getLoopbackAddress());SessionCipher crypto=new SessionCipher(key,65001)){
            rtsp.setSoTimeout(5000);media.setSoTimeout(5000);input.setSoTimeout(5000);
            worker=new Thread(()->client.run(rtsp.getLocalPort()));worker.start();
            try(Socket control=rtsp.accept()){
                control.setSoTimeout(5000);InputStream rx=control.getInputStream();OutputStream tx=control.getOutputStream();
                check(new String(read(rx),StandardCharsets.US_ASCII).startsWith("ANNOUNCE"));
                write(tx,b("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"));
                write(tx,b("ANNOUNCE * RTSP/1.0\r\nCSeq: 1\r\n\r\nencrypt_list=aes128ctr;version=1\r\n"));
                check(new String(read(rx),StandardCharsets.US_ASCII).startsWith("RTSP/1.0 200"));
                write(tx,crypto.encrypt(b("GET_PARAMETER * RTSP/1.0\r\nCSeq: 2\r\n\r\nhis_uibc_capability\r\n")));
                check(new String(crypto.decrypt(read(rx)),StandardCharsets.US_ASCII).contains("left_point_down"));
                write(tx,crypto.encrypt(b("SET_PARAMETER * RTSP/1.0\r\nCSeq: 2\r\n\r\nhis_presentation_url: rtsp://127.0.0.1/hisight/streamid=0 none\r\nhis_trigger_method: SETUP\r\n")));
                check(new String(crypto.decrypt(read(rx)),StandardCharsets.US_ASCII).startsWith("RTSP/1.0 200"));
                check(new String(crypto.decrypt(read(rx)),StandardCharsets.US_ASCII).startsWith("SETUP"));
                write(tx,crypto.encrypt(b("RTSP/1.0 200 OK\r\nCSeq: 3\r\nTransport: RTP/AVP/UDP;unicast;server_port="+media.getLocalPort()+";remotectl_port="+input.getLocalPort()+"\r\n\r\n")));
                // Old receiver fails here: it never connects the reverse channel.
                try(Socket video=media.accept();Socket reverse=input.accept()){
                    check(new String(crypto.decrypt(read(rx)),StandardCharsets.US_ASCII).startsWith("PLAY"));
                    write(tx,crypto.encrypt(b("RTSP/1.0 200 OK\r\nCSeq: 4\r\n\r\n")));
                    byte[] encrypted=crypto.encrypt(elementary);int size=encrypted.length+8;
                    byte[] pes=NearbyWire.join(new byte[]{0,0,1,(byte)224,(byte)(size>>>8),(byte)size,(byte)132,(byte)128,5,0,0,0,0,0},encrypted);
                    byte[] rtp=NearbyWire.join(new byte[]{(byte)128,(byte)224,0,1,0,0,0,1,0,0,0,0},pes);
                    write(video.getOutputStream(),rtp);
                    check(frame.await(3,TimeUnit.SECONDS));check(failure.get()==null);
                    if(args.length>0 && args[0].equals("--media-idle")){
                        // Pause midway through a record header, then midway through its payload.
                        // A retry around readInt/readFully would lose framing after either timeout.
                        DataOutputStream fragmented=new DataOutputStream(video.getOutputStream());
                        fragmented.writeShort(0);fragmented.flush();
                        Thread.sleep(17000);check(worker.isAlive()&&router.ready());
                        fragmented.writeShort(rtp.length);fragmented.write(rtp,0,5);fragmented.flush();
                        Thread.sleep(2000);check(worker.isAlive()&&router.ready());
                        fragmented.write(rtp,5,rtp.length-5);fragmented.flush();
                        long deadline=System.nanoTime()+3000000000L;
                        while(delivered.get()<2&&System.nanoTime()<deadline)Thread.sleep(20);
                        check(delivered.get()==2&&failure.get()==null);
                        check(logs.stream().anyMatch(s->s.contains("视频暂未更新")));
                        check(logs.stream().anyMatch(s->s.contains("视频数据已恢复")));
                        System.out.println("PASS: video idle beyond 15 seconds keeps session and input; split header/payload survive timeouts and video resumes without framing loss");
                    }
                    if(args.length>0 && args[0].equals("--idle")){
                        // Media remains active while RTSP is intentionally silent beyond 30s.
                        for(int seq=2;seq<=35;seq++){
                            Thread.sleep(1000);rtp[2]=(byte)(seq>>>8);rtp[3]=(byte)seq;
                            write(video.getOutputStream(),rtp);
                            check(worker.isAlive() && router.ready());
                        }
                        check(delivered.get()>=30);
                        System.out.println("PASS: 34 seconds RTSP silence with active encrypted video, no false disconnect");
                    }
                    check(router.ready());check(router.touch(0,100,200));check(router.touch(2,110,210));check(router.touch(1,110,210));check(router.key(4));
                    reverse.setSoTimeout(5000);DataInputStream events=new DataInputStream(reverse.getInputStream());
                    for(int action:new int[]{0,2,1,3,4}){
                        check(events.readUnsignedShort()==0);int length=events.readUnsignedShort();check(length>=32&&length<1500);byte[] record=new byte[length-4];events.readFully(record);
                        byte[] plain=crypto.decrypt(record);check(plain[0]==(byte)255&&plain[5]==(byte)255&&(plain[6]&255)==action);
                    }
                }
            }
        }finally{active.set(false);client.close();if(worker!=null)worker.join(2000);}
        check(sockets.isEmpty()&&!router.ready());check(logs.stream().anyMatch(s->s.contains("反向通道已连接")));
        System.out.println("PASS: real localhost TCP ANNOUNCE/SETUP, video+reverse connections before PLAY, encrypted RTP/PES reaches video sink, UIBC capabilities and down/move/up/key packets round-trip, sockets and input binding cleaned up");
    }
}
