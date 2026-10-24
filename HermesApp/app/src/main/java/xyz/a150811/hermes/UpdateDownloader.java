package xyz.a150811.hermes;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resumable APK downloader. GitHub release assets often stall on mobile
 * networks, so every attempt has short timeouts, an inactivity watchdog and
 * Range-based resume; the partial file survives between attempts and app runs.
 */
public final class UpdateDownloader {

    public interface Listener {
        void onProgress(long bytes, long total);

        void onRetry(int attempt, long bytes);

        void onDone();

        void onError(String message);
    }

    private static final int ATTEMPTS = 6;
    private static final int CONNECT_MS = 10000;
    private static final int READ_MS = 20000;
    private static final long STALL_MS = 15000;
    private static final long PAUSE_MS = 1200;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile HttpURLConnection current;
    private volatile Listener listener;

    public UpdateDownloader() {
    }

    public void cancel() {
        cancelled.set(true);
        HttpURLConnection c = current;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public void download(final String url, final long expectedSize, final File target,
                         final Listener l) {
        cancelled.set(false);
        listener = l;
        new Thread(() -> {
            String error = run(url, expectedSize, target);
            if (error == null) {
                ui.post(() -> {
                    Listener cb = listener;
                    if (cb != null) cb.onDone();
                });
            } else {
                ui.post(() -> {
                    Listener cb = listener;
                    if (cb != null) cb.onError(error);
                });
            }
        }, "hermes-update").start();
    }

    private String run(String url, long expected, File target) {
        long have = target.exists() ? target.length() : 0;
        if (expected > 0 && have > expected) {
            target.delete();
            have = 0;
        }
        String lastError = "下载失败";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1) {
                final long at = have;
                final int no = attempt;
                ui.post(() -> {
                    Listener cb = listener;
                    if (cb != null) cb.onRetry(no, at);
                });
                try {
                    Thread.sleep(PAUSE_MS);
                } catch (InterruptedException ie) {
                    return "已取消";
                }
            }
            lastError = attemptOnce(url, target, have);
            if (lastError == null) {
                long size = target.length();
                if (expected > 0 && size != expected) {
                    target.delete();
                    have = 0;
                    lastError = "文件不完整（" + size + "/" + expected + " 字节）";
                    continue;
                }
                return null;
            }
            if (cancelled.get()) return "已取消";
            have = target.exists() ? target.length() : 0;
        }
        return lastError;
    }

    /** Returns null on success, an error message otherwise; keeps partial data. */
    private String attemptOnce(String url, File target, long have) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            current = conn;
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent",
                    "Hermes-Android/" + BuildConfig.VERSION_NAME);
            boolean resume = have > 0;
            if (resume) {
                conn.setRequestProperty("Range", "bytes=" + have + "-");
            }
            int code = conn.getResponseCode();
            if (code == 200 && resume) {
                conn.disconnect();
                return attemptOnce(url, target, 0);
            }
            if (code != 200 && code != 206) {
                return "HTTP " + code;
            }
            long total = code == 206
                    ? have + conn.getContentLengthLong()
                    : conn.getContentLengthLong();
            in = conn.getInputStream();
            out = new FileOutputStream(target, code == 206);
            byte[] buf = new byte[16384];
            long written = have;
            long lastTick = 0;
            long lastData = SystemClock.elapsedRealtime();
            while (true) {
                if (cancelled.get()) return "已取消";
                if (SystemClock.elapsedRealtime() - lastData > STALL_MS) {
                    return "网络无响应，正在重连";
                }
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buf, 0, n);
                written += n;
                lastData = SystemClock.elapsedRealtime();
                if (lastData - lastTick >= 200) {
                    lastTick = lastData;
                    postProgress(written, total);
                }
            }
            out.flush();
            if (total > 0 && written < total) {
                return "连接中断，正在断点续传";
            }
            postProgress(written, total);
            return null;
        } catch (SocketTimeoutException e) {
            return "连接超时";
        } catch (IOException e) {
            if (cancelled.get()) return "已取消";
            String msg = e.getMessage();
            return msg == null || msg.isEmpty() ? "网络错误" : msg;
        } catch (Exception e) {
            return e.toString();
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            current = null;
        }
    }

    private void postProgress(final long bytes, final long total) {
        ui.post(() -> {
            Listener cb = listener;
            if (cb != null) cb.onProgress(bytes, total);
        });
    }
}
