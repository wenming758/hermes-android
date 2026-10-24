package xyz.a150811.hermes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class UpdateChecker {
    private static final String REPO = "wenming758/hermes-android";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String LATEST = "https://github.com/" + REPO + "/releases/latest";

    public static final class Release {
        public final String version;
        public final String notes;
        public final String apkUrl;
        public final long size;

        Release(String version, String notes, String apkUrl, long size) {
            this.version = version;
            this.notes = notes == null ? "" : notes;
            this.apkUrl = apkUrl;
            this.size = size;
        }
    }

    public interface Callback {
        void onResult(Release release, String error);
    }

    private UpdateChecker() {
    }

    public static void check(final Context context, final Callback callback) {
        new Thread(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchFromApi();
            } catch (Exception apiError) {
                try {
                    release = fetchFromRedirect();
                } catch (Exception e) {
                    error = apiError.getMessage();
                    if (error == null || error.isEmpty()) error = e.toString();
                }
            }
            final Release result = release;
            final String message = error;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result, message));
        }).start();
    }

    private static Release fetchFromApi() throws Exception {
        HttpURLConnection conn = open(API);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            JSONObject json = new JSONObject(read(conn.getInputStream()));
            String tag = json.optString("tag_name", "");
            String version = stripV(tag);
            String notes = json.optString("body", "");
            String apkUrl = null;
            long size = 0;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "");
                        size = a.optLong("size", 0);
                        break;
                    }
                }
            }
            if (apkUrl == null || apkUrl.isEmpty()) {
                throw new Exception("no apk asset");
            }
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return null;
            return new Release(version, notes, apkUrl, size);
        } finally {
            conn.disconnect();
        }
    }

    private static Release fetchFromRedirect() throws Exception {
        HttpURLConnection conn = open(LATEST);
        conn.setInstanceFollowRedirects(false);
        try {
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (code / 100 != 3 || location == null) throw new Exception("HTTP " + code);
            String tag = location.substring(location.lastIndexOf('/') + 1);
            String version = stripV(tag);
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return null;
            String url = "https://github.com/" + REPO + "/releases/download/"
                    + tag + "/Hermes-" + version + ".apk";
            return new Release(version, "", url, 0);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "Hermes-Android/" + BuildConfig.VERSION_NAME);
        return conn;
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    static boolean isNewer(String remote, String local) {
        int[] a = parse(remote);
        int[] b = parse(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null) return new int[]{0};
        String[] parts = version.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
