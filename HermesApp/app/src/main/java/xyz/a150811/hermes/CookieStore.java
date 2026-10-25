package xyz.a150811.hermes;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.CookieManager;

import java.util.Map;

/**
 * Backs up WebView login cookies to disk and re-injects them after process
 * death. fnOS issues session cookies without an expiry, which the system
 * cookie jar drops when the OS kills the app (lock screen, swipe away), so
 * every relaunch used to demand a fresh login.
 */
public final class CookieStore {
    private static final String PREF = "hermes_cookies";

    private CookieStore() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Persists the current cookie string of the URL's origin. */
    public static void save(Context c, String url) {
        String base = baseOf(url);
        if (base == null) return;
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie == null || cookie.isEmpty()) return;
            if (cookie.equals(p(c).getString(base, null))) return;
            p(c).edit().putString(base, cookie).apply();
        } catch (Exception ignored) {
        }
    }

    /** Re-injects the saved cookie when the jar has none for this origin. */
    public static void restore(Context c, String url) {
        String base = baseOf(url);
        if (base == null) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            String current = cm.getCookie(url);
            if (current != null && !current.isEmpty()) return;
            String saved = p(c).getString(base, null);
            if (saved == null || saved.isEmpty()) return;
            cm.setCookie(base + "/", saved);
            cm.flush();
        } catch (Exception ignored) {
        }
    }

    /** Re-injects every saved origin; called once when the process starts. */
    public static void restoreAll(Context c) {
        try {
            CookieManager cm = CookieManager.getInstance();
            boolean changed = false;
            for (Map.Entry<String, ?> e : p(c).getAll().entrySet()) {
                String base = e.getKey();
                String saved = String.valueOf(e.getValue());
                if (base == null || saved.isEmpty()) continue;
                String current = cm.getCookie(base + "/");
                if (current != null && !current.isEmpty()) continue;
                cm.setCookie(base + "/", saved);
                changed = true;
            }
            if (changed) cm.flush();
        } catch (Exception ignored) {
        }
    }

    /** Read-only access for non-WebView request paths (probe fallback). */
    public static String saved(Context c, String url) {
        String base = baseOf(url);
        if (base == null) return null;
        try {
            return p(c).getString(base, null);
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context c) {
        p(c).edit().clear().apply();
    }

    /** Scheme + host + port of a URL, e.g. "http://nas.local:5666". */
    private static String baseOf(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null || host.isEmpty()) return null;
            int port = u.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }
}
