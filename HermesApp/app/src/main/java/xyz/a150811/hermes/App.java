package xyz.a150811.hermes;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.webkit.CookieManager;

public class App extends Application {
    public static final String CHANNEL_ID = "hermes_default";
    private static App instance;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CookieManager.getInstance().setAcceptCookie(true);
        CookieStore.restoreAll(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.notify_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
