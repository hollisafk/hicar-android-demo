package local.hicarlab;
import android.app.Activity;
import android.os.Bundle;
import android.content.res.Configuration;
import android.view.View;

/** Theme recreation keeps the application-owned receiver/session alive. */
public class ThemeActivity extends Activity {
    private boolean dark;
    @Override protected void onCreate(Bundle state){
        dark=UiTheme.dark(this);setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);getWindow().setStatusBarColor(UiTheme.background(this));getWindow().setNavigationBarColor(UiTheme.background(this));
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    @Override protected void onResume(){super.onResume();if(dark!=UiTheme.dark(this))recreate();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(dark!=UiTheme.dark(this))recreate();}
}
