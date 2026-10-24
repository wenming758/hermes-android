package xyz.a150811.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class UpdateFlow {
    private final Activity activity;
    private Button button;

    private UpdateChecker.Release pending;
    private UpdateDownloader downloader;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private boolean permissionResume;
    private boolean busy;

    public UpdateFlow(Activity activity) {
        this.activity = activity;
    }

    public void bind(Button btn) {
        button = btn;
        btn.setOnClickListener(v -> check());
    }

    public void check() {
        if (busy) {
            UiUtil.toast(activity, activity.getString(R.string.update_downloading));
            return;
        }
        if (button != null) {
            button.setEnabled(false);
            button.setText(R.string.update_checking);
        }
        UpdateChecker.check(activity, (release, error) -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            resetButton();
            if (error != null) {
                UiUtil.toast(activity, activity.getString(R.string.update_failed, error));
                return;
            }
            if (release == null) {
                UiUtil.toast(activity, activity.getString(R.string.update_latest,
                        BuildConfig.VERSION_NAME));
                return;
            }
            showDialog(release);
        });
    }

    private void resetButton() {
        if (button == null) return;
        button.setEnabled(true);
        button.setText(R.string.update_check);
    }

    private void showDialog(UpdateChecker.Release release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.isEmpty()) notes = activity.getString(R.string.update_notes_empty);
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        notes += "\n\n" + activity.getString(R.string.update_source);
        new AlertDialog.Builder(activity, R.style.DialogTheme)
                .setTitle(activity.getString(R.string.update_found_title, release.version))
                .setMessage(notes)
                .setPositiveButton(R.string.update_download, (d, w) -> startDownload(release))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void startDownload(UpdateChecker.Release release) {
        if (busy) return;
        if (!canInstall()) {
            pending = release;
            permissionResume = true;
            new AlertDialog.Builder(activity, R.style.DialogTheme)
                    .setTitle(R.string.update_allow_title)
                    .setMessage(R.string.update_allow_msg)
                    .setPositiveButton(R.string.update_allow_go,
                            (d, w) -> openInstallPermission())
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
            return;
        }
        busy = true;
        File dir = new File(activity.getFilesDir(), "update");
        dir.mkdirs();
        cleanupOld(dir, release.version);
        final File target = new File(dir, "Hermes-" + release.version + ".apk");
        if (target.exists() && release.size > 0 && target.length() == release.size) {
            installSession(target);
            return;
        }
        showProgressDialog(release);
        if (button != null) button.setEnabled(false);
        downloader = new UpdateDownloader();
        downloader.download(release.apkUrl, release.size, target,
                new UpdateDownloader.Listener() {
                    @Override
                    public void onProgress(long bytes, long total) {
                        if (progressBar == null || activity.isFinishing()) return;
                        int pct = total > 0 ? (int) (bytes * 100 / total) : 0;
                        progressBar.setProgress(pct);
                        String done = mb(bytes);
                        progressText.setText(total > 0
                                ? activity.getString(R.string.update_progress_mb,
                                        done, mb(total), pct)
                                : activity.getString(R.string.update_progress_bytes, done));
                    }

                    @Override
                    public void onRetry(int attempt, long bytes) {
                        if (progressText == null || activity.isFinishing()) return;
                        progressText.setText(activity.getString(
                                R.string.update_progress_retry, attempt, mb(bytes)));
                    }

                    @Override
                    public void onDone() {
                        hideProgressDialog();
                        installSession(target);
                    }

                    @Override
                    public void onError(String message) {
                        hideProgressDialog();
                        resetButton();
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if ("已取消".equals(message)) return;
                        new AlertDialog.Builder(activity, R.style.DialogTheme)
                                .setTitle(R.string.update_download_error_title)
                                .setMessage(activity.getString(
                                        R.string.update_download_error_msg, message))
                                .setPositiveButton(R.string.update_download_retry,
                                        (d, w) -> startDownload(release))
                                .setNegativeButton(R.string.dialog_cancel, null)
                                .show();
                    }
                });
    }

    private void showProgressDialog(UpdateChecker.Release release) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiUtil.dp(activity, 24);
        box.setPadding(pad, UiUtil.dp(activity, 12), pad, UiUtil.dp(activity, 4));

        progressBar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        box.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        progressText = new TextView(activity);
        progressText.setTextSize(13);
        progressText.setTextColor(activity.getColor(R.color.ink_soft));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = UiUtil.dp(activity, 10);
        box.addView(progressText, tp);

        progressDialog = new AlertDialog.Builder(activity, R.style.DialogTheme)
                .setTitle(activity.getString(R.string.update_progress_title, release.version))
                .setView(box)
                .setNegativeButton(R.string.update_progress_cancel, (d, w) -> cancelDownload())
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void hideProgressDialog() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
    }

    private void cancelDownload() {
        if (downloader != null) downloader.cancel();
        busy = false;
        resetButton();
    }

    /** Installs the downloaded APK via a PackageInstaller session: the system
     *  shows its confirm dialog once, then replaces the app in place. */
    private void installSession(final File apk) {
        if (button != null) button.setText(R.string.update_installing);
        new Thread(() -> {
            String error = null;
            try {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
                int id = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(id);
                try {
                    OutputStream out = session.openWrite("hermes-update", 0, apk.length());
                    InputStream in = new FileInputStream(apk);
                    byte[] buf = new byte[16384];
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                        session.fsync(out);
                    } finally {
                        try {
                            in.close();
                        } catch (Exception ignored) {
                        }
                        try {
                            out.close();
                        } catch (Exception ignored) {
                        }
                    }
                    Intent status = new Intent(activity, UpdateInstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getBroadcast(activity, id, status,
                            flags).getIntentSender());
                } finally {
                    session.close();
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String msg = error;
            activity.runOnUiThread(() -> {
                busy = false;
                resetButton();
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (msg != null) {
                    UiUtil.toast(activity, activity.getString(
                            R.string.update_install_failed));
                }
            });
        }, "hermes-install").start();
    }

    public void onResume() {
        if (permissionResume && pending != null && canInstall()) {
            permissionResume = false;
            UpdateChecker.Release release = pending;
            startDownload(release);
        }
    }

    public void destroy() {
        if (downloader != null) downloader.cancel();
        hideProgressDialog();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void openInstallPermission() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            UiUtil.toast(activity, activity.getString(R.string.update_install_failed));
        }
    }

    /** Deletes APKs of other versions left in the update dir. */
    private void cleanupOld(File dir, String keepVersion) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().equals("Hermes-" + keepVersion + ".apk")) continue;
            try {
                f.delete();
            } catch (Exception ignored) {
            }
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576f);
    }
}
