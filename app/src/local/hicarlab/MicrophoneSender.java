package local.hicarlab;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.Arrays;

/** Bounded live capture -> AAC -> authenticated phone UDP; never writes audio files. */
final class MicrophoneSender implements MicrophoneControl.Output,DeviceBusiness.Speaker,AutoCloseable {
    interface Events {boolean active();void log(String text);}
    private final Context context;private final Events events;private final DatagramSocket socket;private final InetAddress peer;private final SessionCipher cipher;
    private volatile boolean closed,recording;private volatile int generation;
    private volatile AudioRecord recorder;private Thread worker;
    private volatile String status="麦克风待命";
    private long peerUs,peerNanos;
    MicrophoneSender(Context c,String owner,String peer,byte[] key,Events events)throws IOException{
        context=c.getApplicationContext();this.events=events;this.peer=InetAddress.getByName(peer);socket=new DatagramSocket(new InetSocketAddress(owner,0));cipher=new SessionCipher(key,65536);
    }
    static boolean enabled(Context c){return c.getSharedPreferences("ui",0).getBoolean("microphone",false)&&(android.os.Build.VERSION.SDK_INT<23||c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED);}
    public boolean allowed(){return !closed&&events.active()&&enabled(context);}
    public boolean ready(){return allowed();}public int port(){return socket.getLocalPort();}
    String status(){return status;}
    synchronized void clock(long high,long low){long value=((high&0xffffffffL)<<32)|(low&0xffffffffL);if(value>0){peerUs=value;peerNanos=System.nanoTime();}}
    private synchronized long nowPeer(){return peerUs>0?peerUs+(System.nanoTime()-peerNanos)/1000:System.nanoTime()/1000;}
    public void log(String text){events.log(text);}
    public synchronized void start(int remote)throws IOException{
        if(!allowed()||remote<1||remote>65535)throw new IOException("microphone disabled or endpoint");
        stop();final int token=++generation;worker=new Thread(()->capture(token,remote),"lab-microphone");worker.start();
    }
    private void capture(int token,int remote){
        AudioRecord record=null;MediaCodec encoder=null;short[] pcm=new short[1024];byte[] raw=null;
        try{
            if(!allowed()||generation!=token)return;
            int min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IOException("microphone format unavailable");
            record=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,8192));recorder=record;
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("microphone initialization");
            encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");MediaFormat format=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,2);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);format.setInteger(MediaFormat.KEY_BIT_RATE,64000);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096);
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();
            if(!allowed()||generation!=token)return;record.startRecording();
            if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("microphone capture refused");
            if(!allowed()||generation!=token)return;
            recording=true;status="麦克风正在采集";events.log("麦克风开始采集：单声道 PCM 转 AAC/48000 Hz/双声道，仅发送到当前认证手机。");
            long origin=nowPeer(),samples=0,packets=0,lastEncoded=System.nanoTime(),lastConfig=0;boolean silenced=false;int filled=0,sequence=0;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(generation==token&&allowed()){
                if(android.os.Build.VERSION.SDK_INT>=29&&System.nanoTime()-lastConfig>500000000L){
                    lastConfig=System.nanoTime();AudioRecordingConfiguration config=record.getActiveRecordingConfiguration();
                    boolean muted=config!=null&&config.isClientSilenced();
                    if(muted!=silenced){silenced=muted;status=muted?"麦克风被系统静音，请检查其他应用通话或录音":"麦克风正在采集";
                        events.log(muted?"系统报告本应用录音被静音；未读取其他应用录音信息。":"系统已恢复本应用麦克风输入。");}
                }
                // 4 参 read()/READ_BLOCKING 是 API 23；Android 5.x 只能用 3 参阻塞读。
                int n=android.os.Build.VERSION.SDK_INT>=23?record.read(pcm,filled,pcm.length-filled,AudioRecord.READ_BLOCKING):record.read(pcm,filled,pcm.length-filled);
                if(n<=0)throw new IOException("microphone read");filled+=n;if(filled<pcm.length)continue;
                int index=encoder.dequeueInputBuffer(10000);
                if(index>=0){ByteBuffer input=encoder.getInputBuffer(index);if(input==null||input.capacity()<4096)throw new IOException("microphone encoder input");input.clear();input.order(ByteOrder.LITTLE_ENDIAN);for(short value:pcm){input.putShort(value);input.putShort(value);}encoder.queueInputBuffer(index,0,4096,samples*1000000L/48000,0);}
                samples+=pcm.length;filled=0;Arrays.fill(pcm,(short)0);
                for(int i=0;i<8;i++){
                    int output=encoder.dequeueOutputBuffer(info,0);if(output<0)break;
                    try{if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&info.size>0){
                        ByteBuffer buffer=encoder.getOutputBuffer(output);if(buffer==null||info.size>8184)throw new IOException("microphone encoder output");raw=new byte[info.size];buffer.position(info.offset);buffer.limit(info.offset+info.size);buffer.get(raw);
                        byte[] rtp=MicrophoneWire.packet(raw,sequence++,MicrophoneWire.timestamp(origin,Math.max(0,info.presentationTimeUs)));
                        byte[] encrypted=cipher.encrypt(rtp);byte[] packet=NearbyWire.join(new byte[]{(byte)171,(byte)(encrypted.length>>>8),(byte)encrypted.length},encrypted);
                        try{if(generation!=token||!allowed())break;socket.send(new DatagramPacket(packet,packet.length,peer,remote));packets++;lastEncoded=System.nanoTime();
                            if(packets==1||packets%200==0)events.log("麦克风加密上行："+packets+" 个 AAC 包；尚需手机识别结果确认。");
                        }finally{Arrays.fill(raw,(byte)0);raw=null;Arrays.fill(rtp,(byte)0);Arrays.fill(encrypted,(byte)0);Arrays.fill(packet,(byte)0);}
                    }}finally{encoder.releaseOutputBuffer(output,false);}
                }
                if(System.nanoTime()-lastEncoded>3000000000L)throw new IOException("microphone encoder timeout");
            }
        }catch(Exception e){if(generation==token&&allowed()){status="麦克风暂不可用，请关闭后重新启用";events.log("麦克风采集结束："+e.getClass().getSimpleName()+"；画面及播放继续。");}}
        finally{
            Arrays.fill(pcm,(short)0);if(raw!=null)Arrays.fill(raw,(byte)0);
            if(record!=null){try{record.stop();}catch(RuntimeException ignored){}record.release();if(recorder==record)recorder=null;}
            if(encoder!=null){try{encoder.stop();}catch(RuntimeException ignored){}encoder.release();}
            if(generation==token){recording=false;if(!status.startsWith("麦克风暂"))status=enabled(context)?"麦克风待命":"麦克风已关闭";}
        }
    }
    public synchronized void stop(){
        generation++;recording=false;AudioRecord current=recorder;if(current!=null)try{current.stop();}catch(RuntimeException ignored){}
        Thread old=worker;worker=null;if(old!=null&&old!=Thread.currentThread())try{old.join(700);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        status=enabled(context)?"麦克风待命":"麦克风已关闭";
    }
    public synchronized void close(){if(closed)return;closed=true;stop();socket.close();cipher.close();}
}
