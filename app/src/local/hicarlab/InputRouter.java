package local.hicarlab;

/** Only the current authenticated input session can receive UI events. */
final class InputRouter {
    interface Sender {boolean send(UiInput.Event event);}
    private Sender sender;private boolean pressed;private int x,y;
    synchronized void attach(Sender next){sender=next;pressed=false;}
    synchronized void detach(Sender old){if(sender==old){sender=null;pressed=false;}}
    synchronized boolean ready(){return sender!=null;}
    synchronized boolean touch(int action,int nx,int ny){
        if(sender==null||nx<0||nx>=VideoSpec.WIDTH||ny<0||ny>=VideoSpec.HEIGHT||action<0||action>2)return false;
        if(action==0&&pressed||action!=0&&!pressed)return false;
        if(!sender.send(UiInput.Event.touch(action,nx,ny)))return false;
        x=nx;y=ny;pressed=action!=1;return true;
    }
    synchronized void cancel(){if(pressed&&sender!=null)sender.send(UiInput.Event.touch(1,x,y));pressed=false;}
    synchronized boolean key(int key){
        if(sender==null||(key!=3&&key!=4))return false;
        cancel();if(!sender.send(UiInput.Event.key(key,true)))return false;return sender.send(UiInput.Event.key(key,false));
    }
    static int[] map(float x,float y,int width,int height,boolean clamp){
        if(width<1||height<1||Float.isNaN(x)||Float.isInfinite(x)||Float.isNaN(y)||Float.isInfinite(y))return null;
        if(!clamp&&(x<0||x>=width||y<0||y>=height))return null;
        int px=Math.max(0,Math.min(VideoSpec.WIDTH-1,(int)Math.floor(x*VideoSpec.WIDTH/(double)width)));
        int py=Math.max(0,Math.min(VideoSpec.HEIGHT-1,(int)Math.floor(y*VideoSpec.HEIGHT/(double)height)));
        return new int[]{px,py};
    }
}
