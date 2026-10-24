package xyz.a150811.hermes;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String PREF = "hermes_settings";

    private Prefs() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean autoConnect(Context c) {
        return p(c).getBoolean("auto_connect", false);
    }

    public static void setAutoConnect(Context c, boolean v) {
        p(c).edit().putBoolean("auto_connect", v).apply();
    }

    public static boolean desktopUa(Context c) {
        return p(c).getBoolean("desktop_ua", false);
    }

    public static void setDesktopUa(Context c, boolean v) {
        p(c).edit().putBoolean("desktop_ua", v).apply();
    }

    public static boolean keepScreenOn(Context c) {
        return p(c).getBoolean("keep_screen_on", false);
    }

    public static void setKeepScreenOn(Context c, boolean v) {
        p(c).edit().putBoolean("keep_screen_on", v).apply();
    }

    public static boolean immersive(Context c) {
        return p(c).getBoolean("immersive_nav", true);
    }

    public static void setImmersive(Context c, boolean v) {
        p(c).edit().putBoolean("immersive_nav", v).apply();
    }

    public static void setFnosSession(Context c, boolean v) {
        p(c).edit().putBoolean("fnos_session", v).apply();
    }

    public static boolean fnosSession(Context c) {
        return p(c).getBoolean("fnos_session", false);
    }
}
