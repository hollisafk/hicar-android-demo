package local.hicarlab;

import android.content.Context;
import android.os.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Bounded protocol metadata only: never PIN, packet bodies, addresses or keys. */
final class DiagnosticLog {
    interface Listener { void update(String text); }
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static StringBuilder history;
    private static Listener listener;
    static synchronized String history(Context c) {
        if(history==null)history=new StringBuilder(c.getSharedPreferences("diagnostics",0).getString("history",""));
        return history.toString();
    }
    static void add(Context c,String message) {
        final String snapshot;
        synchronized(DiagnosticLog.class) {
            history(c);
            history.append(new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())).append(' ').append(message).append('\n');
            if(history.length()>24000)history.delete(0,history.length()-16000);
            snapshot=history.toString();
            c.getSharedPreferences("diagnostics",0).edit().putString("history",snapshot).apply();
        }
        android.util.Log.i("HiCarLab",message);
        handler.post(()->{ Listener target; synchronized(DiagnosticLog.class){target=listener;} if(target!=null)target.update(snapshot); });
    }
    static void attach(Context c,Listener target) { synchronized(DiagnosticLog.class){listener=target;} target.update(history(c)); }
    static synchronized void detach(Listener target) {if(listener==target)listener=null;}
}
