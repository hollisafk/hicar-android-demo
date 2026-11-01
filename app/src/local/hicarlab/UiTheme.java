package local.hicarlab;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;

final class UiTheme {
    static int mode(Context c){return c.getSharedPreferences("ui",0).getInt("theme",0);}
    static boolean dark(Context c){int m=mode(c);return m==2 || m==0 && (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    static int background(Context c){return dark(c)?0xff11151c:0xfff4f6fa;}
    static int card(Context c){return dark(c)?0xff202733:0xffffffff;}
    static int text(Context c){return dark(c)?0xffedf2fa:0xff182438;}
    static int accent(Context c){return dark(c)?0xffa8caff:0xff183d74;}
    static String label(Context c){return new String[]{"跟随系统","浅色","深色"}[Math.max(0,Math.min(2,mode(c)))];}
    static void choose(Activity a){new AlertDialog.Builder(a).setTitle("外观模式").setSingleChoiceItems(new String[]{"跟随系统","浅色","深色"},mode(a),(dialog,which)->{
        a.getSharedPreferences("ui",0).edit().putInt("theme",which).apply();dialog.dismiss();a.recreate();
    }).setNegativeButton("取消",null).show();}
}
