package local.hicarlab;
import java.io.*;
import java.net.*;
import java.security.GeneralSecurityException;
import java.util.*;

/** Only connects to endpoints supplied by the already selected authenticated peer. */
final class HiSightClient implements AutoCloseable {
    interface Video {boolean ready();void offer(int kind,long timestamp,byte[] data)throws IOException;}
    interface Events {boolean active();void log(String text);void add(Socket s);void remove(Socket s);}
    private final String owner,peer;private final Video sink;private final Events events;
    private final InputRouter router;private InputChannel inputChannel;
    private byte[] key;private volatile boolean closed;private Socket control,media,input;
    private long lastMediaRead;private boolean mediaIdle;
    HiSightClient(String own,String remote,byte[] sessionKey,Video video,Events e){this(own,remote,sessionKey,video,e,null);}
    HiSightClient(String own,String remote,byte[] sessionKey,Video video,Events e,InputRouter r){owner=own;peer=remote;key=sessionKey.clone();sink=video;events=e;router=r;}
    private Socket connect(int port)throws IOException{
        if(port<1||port>65535||closed||!events.active())throw new IOException("video endpoint state");
        Socket s=new Socket();events.add(s);
        try{s.bind(new InetSocketAddress(owner,0));s.connect(new InetSocketAddress(peer,port),5000);s.setSoTimeout(1000);s.setTcpNoDelay(true);return s;}
        catch(IOException e){events.remove(s);try{s.close();}catch(IOException ignored){}throw e;}
    }
    void run(int port){
        HiSightWire protocol=null;
        try{
            protocol=new HiSightWire(key,events::log,router!=null);control=connect(port);InputStream in=control.getInputStream();OutputStream out=control.getOutputStream();
            out.write(protocol.initial());out.flush();events.log("HiSight ANNOUNCE 已发送；等待手机视频协议协商。");
            byte[] buffer=new byte[4096];long last=System.nanoTime();
            while(!closed&&events.active()&&sink.ready()&&!protocol.ended()){
                int n;try{n=in.read(buffer);}catch(SocketTimeoutException e){if(media==null && System.nanoTime()-last>30000000000L)throw e;continue;}
                if(n<0)break;last=System.nanoTime();List<byte[]> replies=protocol.feed(buffer,n);
                if(protocol.mediaPort()!=0&&media==null){media=connect(protocol.mediaPort());
                    if(protocol.inputPort()!=0){input=connect(protocol.inputPort());events.log("HiSight 反向通道已连接。");
                        if(router!=null){inputChannel=new InputChannel(input,key,router,events);inputChannel.start();events.log("UIBC 输入发送通道已就绪；仅发送用户在视频窗口中的操作。");}}
                    Thread rx=new Thread(this::receiveMedia,"lab-hisight-media");rx.start();replies.add(protocol.play());events.log("视频及必要反向通道就绪，准备发送 PLAY。");}
                for(byte[] reply:replies){out.write(reply);out.flush();}
            }
        }catch(IOException|GeneralSecurityException|RuntimeException e){if(events.active())events.log("HiSight 会话结束："+e.getClass().getSimpleName()+"；尚未确认画面时勿视为投屏成功。");}
        finally{
            if(protocol!=null){try{byte[] teardown=protocol.stop();if(teardown!=null&&control!=null&&!control.isClosed()){control.getOutputStream().write(teardown);control.getOutputStream().flush();}}catch(Exception ignored){}protocol.close();}
            close();
        }
    }
    private void receiveMedia(){
        byte[] localKey; synchronized(this){if(closed||key==null)return;localKey=key.clone();}
        try(HiSightMedia decoder=new HiSightMedia(localKey)){
            InputStream in=new BufferedInputStream(media.getInputStream(),65536);
            lastMediaRead=System.nanoTime();mediaIdle=false;
            events.log("HiSight 视频 TCP 已连接，等待加密 H.264 数据。");
            while(!closed&&events.active()&&sink.ready()){
                byte[] header=new byte[4];readMediaFully(in,header);
                long length=0;for(byte value:header)length=(length<<8)|(value&255);
                if(length<1||length>HiSightMedia.LIMIT)throw new IOException("media record size");
                byte[] packet=new byte[(int)length];readMediaFully(in,packet);decoder.packet(packet,(kind,time,data)->{if(closed||!events.active())throw new IOException("stale video session");sink.offer(kind,time,data);});
            }
        }catch(IOException|GeneralSecurityException|RuntimeException e){if(events.active())events.log("HiSight 视频接收结束："+e.getClass().getSimpleName());}
        finally{Arrays.fill(localKey,(byte)0);close();}
    }
    // A locked sender can pause video while its authenticated control heartbeat remains alive.
    // Preserve partial record bytes across read timeouts; control loss still closes this socket.
    private void readMediaFully(InputStream in,byte[] target)throws IOException{
        int offset=0;
        while(offset<target.length){
            if(closed||!events.active()||!sink.ready())throw new IOException("stale video session");
            int n;
            try{n=in.read(target,offset,target.length-offset);}catch(SocketTimeoutException e){
                if(!mediaIdle&&System.nanoTime()-lastMediaRead>=15000000000L){mediaIdle=true;events.log("视频暂未更新；保留会话等待恢复，连接存活仍由控制心跳判断。");}
                continue;
            }
            if(n<0)throw new EOFException("media closed");
            if(n==0)continue;
            offset+=n;lastMediaRead=System.nanoTime();
            if(mediaIdle){mediaIdle=false;events.log("视频数据已恢复，继续当前会话。");}
        }
    }
    public synchronized void close(){
        if(closed)return;closed=true;
        if(inputChannel!=null){inputChannel.close();inputChannel=null;}
        for(Socket s:new Socket[]{control,media,input})if(s!=null){try{s.close();}catch(IOException ignored){}events.remove(s);}
        if(key!=null){Arrays.fill(key,(byte)0);key=null;}
    }
}
