package local.hicarlab;
import java.io.*;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.concurrent.*;

/** Bounded background sender; no network I/O on the UI thread. */
final class InputChannel implements InputRouter.Sender,AutoCloseable {
    private final Socket socket;private final InputRouter router;private final UiInput encoder;
    private final HiSightClient.Events events;
    private final ArrayBlockingQueue<UiInput.Event> pending=new ArrayBlockingQueue<>(64);
    private volatile boolean closed;private final long start=System.nanoTime();
    InputChannel(Socket s,byte[] key,InputRouter r,HiSightClient.Events e){socket=s;encoder=new UiInput(key);router=r;events=e;}
    void start(){router.attach(this);new Thread(this::write,"lab-uibc-writer").start();}
    public boolean send(UiInput.Event event){
        if(closed||!events.active())return false;
        if(pending.offer(event))return true;
        // Moves may be dropped; never silently lose a down/up pair.
        if(event.action==2)return true;
        close();events.log("输入队列已满，关闭输入通道，避免保留按下状态。");return false;
    }
    private void write(){
        long count=0;
        try{
            OutputStream out=socket.getOutputStream();
            while(!closed&&events.active()){
                UiInput.Event event=pending.poll(250,TimeUnit.MILLISECONDS);if(event==null)continue;
                byte[] packet=encoder.encode(event,(System.nanoTime()-start)/1000000L);out.write(packet);out.flush();count++;
                if(count<=3||count%30==0)events.log("已发送加密输入事件 "+count+"；不记录坐标，是否生效请看画面。");
            }
        }catch(IOException|GeneralSecurityException|InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();if(!closed&&events.active())events.log("输入发送停止："+e.getClass().getSimpleName());}
        finally{close();}
    }
    public void close(){if(closed)return;closed=true;router.detach(this);pending.clear();try{socket.close();}catch(IOException ignored){}encoder.close();}
}
