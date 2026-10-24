package xyz.a150811.hermes;

import android.app.Application;
import android.webkit.CookieManager;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CookieManager.getInstance().setAcceptCookie(true);
    }
}
