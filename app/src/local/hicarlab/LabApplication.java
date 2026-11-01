package local.hicarlab;
import android.app.Application;
public final class LabApplication extends Application {
    private ReceiverEngine engine;
    private VideoSink video;
    private final InputRouter input=new InputRouter();
    InputRouter input(){return input;}
    synchronized VideoSink video(){if(video==null)video=new VideoSink(this);return video;}
    synchronized ReceiverEngine engine(){if(engine==null)engine=new ReceiverEngine(this);return engine;}
}
