package local.hicarlab;
import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** AAC-LC -> PCM16 AudioTrack, per native SSRC stream; no recording. */
final class AudioSink implements AudioReceiver.Sink {
    private final Context context;private final AudioManager manager;/** API 26+ 为 AudioFocusRequest；5.x 为 null（走老 API，监听器存 focusListener）。 */
    private final Object focus;private final AudioManager.OnAudioFocusChangeListener focusListener;
    private final Map<Integer,Stream> streams=new HashMap<>();
    private volatile boolean closed,focused;
    AudioSink(Context c)throws IOException{
        context=c.getApplicationContext();manager=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        MediaFormat probe=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,2);
        if(new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe)==null)throw new IOException("AAC decoder unavailable");
        focusListener=this::focusChanged;
        if(Build.VERSION.SDK_INT>=26){
            AudioFocusRequest r=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes()).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener,new Handler(Looper.getMainLooper())).build();
            focus=r;
            focused=manager.requestAudioFocus(r)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if(!focused){manager.abandonAudioFocusRequest(r);throw new IOException("audio focus unavailable");}
        }else{ // 5.x：老三参 API（STREAM_MUSIC + GAIN），语义与 26+ 版本一致
            focus=null;
            focused=manager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if(!focused){manager.abandonAudioFocus(focusListener);throw new IOException("audio focus unavailable");}
        }
        DiagnosticLog.add(context,"AAC 解码能力与音频焦点已就绪；尚未实际解码或出声。");
    }
    private static AudioAttributes attributes(){return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();}
    public boolean ready(){return !closed;}
    private synchronized void focusChanged(int value){
        focused=value==AudioManager.AUDIOFOCUS_GAIN;
        for(Stream s:streams.values())s.focus(focused);
        if(!closed)DiagnosticLog.add(context,focused?"本轮音频焦点已恢复。":"本轮音频焦点暂停，清理播放队列。");
    }
    public synchronized void offer(AudioMedia.Frame frame){
        if(closed||!focused)return;
        Stream stream=streams.get(frame.stream);
        if(stream!=null&&stream.ended&&System.nanoTime()-stream.finished<1000000000L)return;
        if(stream==null||stream.ended||stream.rate!=frame.rate||stream.channels!=frame.channels){
            if(stream!=null)stream.close();else if(streams.size()>=8)return;
            stream=new Stream(frame);streams.put(frame.stream,stream);stream.start();
        }
        stream.offer(frame);
    }
    public synchronized void reset(){for(Stream s:streams.values())s.close();streams.clear();}
    public synchronized void close(){if(closed)return;closed=true;focused=false;reset();if(focus!=null)manager.abandonAudioFocusRequest((AudioFocusRequest)focus);else manager.abandonAudioFocus(focusListener);}
    private final class Stream implements Runnable {
        final int rate,channels,index;
        final ArrayBlockingQueue<byte[]> pending=new ArrayBlockingQueue<>(32);
        volatile boolean ended;volatile AudioTrack track;
        long samples,decoded,nonzero,drops;volatile long finished;
        Stream(AudioMedia.Frame first){rate=first.rate;channels=first.channels;index=first.rateIndex;}
        void start(){new Thread(this,"lab-aac-playback").start();}
        void offer(AudioMedia.Frame f){byte[] data=f.data.clone();if(!pending.offer(data)){byte[] old=pending.poll();if(old!=null)Arrays.fill(old,(byte)0);if(!pending.offer(data))Arrays.fill(data,(byte)0);drops++;}}
        void focus(boolean enabled){
            AudioTrack audio=track;
            if(audio!=null)try{if(enabled)audio.play();else{audio.pause();audio.flush();}}catch(RuntimeException ignored){}
            if(!enabled)clear();
        }
        void clear(){byte[] b;while((b=pending.poll())!=null)Arrays.fill(b,(byte)0);}
        void close(){ended=true;clear();AudioTrack audio=track;if(audio!=null)try{audio.pause();audio.flush();}catch(RuntimeException ignored){}}
        public void run(){
            MediaCodec codec=null;byte[] frame=null;
            try{
                MediaFormat format=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);
                int asc=(2<<11)|(index<<7)|(channels<<3);format.setByteBuffer("csd-0",ByteBuffer.wrap(new byte[]{(byte)(asc>>>8),(byte)asc}));
                codec=MediaCodec.createDecoderByType("audio/mp4a-latm");codec.configure(format,null,null,0);codec.start();
                MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                while(!closed&&!ended){
                    if(!focused){if(frame!=null){Arrays.fill(frame,(byte)0);frame=null;}clear();Thread.sleep(20);continue;}
                    if(frame==null)frame=pending.poll();
                    if(frame!=null){int input=codec.dequeueInputBuffer(5000);if(input>=0){ByteBuffer buffer=codec.getInputBuffer(input);if(buffer==null||buffer.capacity()<frame.length)throw new IOException("AAC input capacity");buffer.clear();buffer.put(frame);codec.queueInputBuffer(input,0,frame.length,samples*1000000L/rate,0);samples+=1024;Arrays.fill(frame,(byte)0);frame=null;}}
                    int output=codec.dequeueOutputBuffer(info,5000);
                    if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        MediaFormat actual=codec.getOutputFormat();int r=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE),ch=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int encoding=actual.containsKey(MediaFormat.KEY_PCM_ENCODING)?actual.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                        if(encoding!=AudioFormat.ENCODING_PCM_16BIT||(ch!=1&&ch!=2))throw new IOException("AAC output PCM format");
                        int mask=ch==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO,min=AudioTrack.getMinBufferSize(r,mask,encoding);if(min<1)throw new IOException("audio buffer unavailable");
                        if(track!=null){track.release();track=null;}
                        int bufBytes=Math.max(min*2,r*ch*2/10);
                        if(Build.VERSION.SDK_INT>=23)track=new AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(new AudioFormat.Builder().setEncoding(encoding).setSampleRate(r).setChannelMask(mask).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufBytes).build();
                        else track=new AudioTrack(AudioManager.STREAM_MUSIC,r,mask,encoding,bufBytes,AudioTrack.MODE_STREAM);
                        if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("AudioTrack state");track.play();
                        DiagnosticLog.add(context,"AAC 解码输出："+r+" Hz，"+ch+" 声道，PCM16；AudioTrack 已开始。");
                    }else if(output>=0){
                        try{
                            if(info.size>0&&track!=null&&focused){ByteBuffer pcm=codec.getOutputBuffer(output);if(pcm==null)throw new IOException("audio output unavailable");pcm.position(info.offset);pcm.limit(info.offset+info.size);boolean signal=false;for(int at=pcm.position();at<pcm.limit();at++)if(pcm.get(at)!=0){signal=true;break;}
                                int total=0;while(pcm.hasRemaining()&&!closed&&!ended&&focused){int n=track.write(pcm,pcm.remaining(),AudioTrack.WRITE_BLOCKING);if(n<0)throw new IOException("audio write failed");if(n==0)break;total+=n;}
                                if(total>0){decoded++;if(signal)nonzero++;if(decoded<=3||decoded%200==0){String routeInfo="";if(Build.VERSION.SDK_INT>=23){AudioDeviceInfo route=track.getRoutedDevice();routeInfo="，输出设备类型 "+(route==null?0:route.getType());}DiagnosticLog.add(context,"PCM 已写入播放设备："+decoded+" 块，非零 "+nonzero+" 块"+routeInfo+"；实际出声请听音确认。");}}
                            }
                        }finally{codec.releaseOutputBuffer(output,false);}
                    }
                }
            }catch(IOException|RuntimeException|InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();if(!closed&&!ended)DiagnosticLog.add(context,"AAC 播放流停止："+e.getClass().getSimpleName());}
            finally{finished=System.nanoTime();ended=true;if(frame!=null)Arrays.fill(frame,(byte)0);clear();if(track!=null){try{track.stop();}catch(RuntimeException ignored){}track.release();track=null;}if(codec!=null){try{codec.stop();}catch(RuntimeException ignored){}codec.release();}}
        }
    }
}
