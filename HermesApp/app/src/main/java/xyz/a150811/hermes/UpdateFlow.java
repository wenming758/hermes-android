package xyz.a150811.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;

public final class UpdateFlow {
    private final Activity activity;
    private Button button;
    private long downloadId = -1;
    private long downloadedId = -1;
    private BroadcastReceiver receiver;

    public UpdateFlow(Activity activity) {
        this.activity = activity;
    }

    public void bind(Button btn) {
        button = btn;
        btn.setOnClickListener(v -> check());
    }

    public void check() {
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
        try {
            DownloadManager.Request req =
                    new DownloadManager.Request(Uri.parse(release.apkUrl));
            req.setTitle("Hermes " + release.version);
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    "Hermes-" + release.version + ".apk");
            DownloadManager dm = (DownloadManager) activity.getSystemService(
                    Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("no download manager");
            downloadId = dm.enqueue(req);
            registerReceiver();
            UiUtil.toast(activity, activity.getString(R.string.update_downloading));
        } catch (Exception e) {
            UiUtil.toast(activity, activity.getString(R.string.update_download_failed));
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) install(id);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                activity.registerReceiver(receiver, filter);
            }
        } catch (Exception e) {
            receiver = null;
        }
    }

    private void unregisterReceiver() {
        if (receiver == null) return;
        try {
            activity.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiver = null;
    }

    public void onResume() {
        if (downloadedId != -1 && canInstall()) {
            long id = downloadedId;
            downloadedId = -1;
            install(id);
        }
    }

    public void destroy() {
        unregisterReceiver();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void install(long id) {
        DownloadManager dm = (DownloadManager) activity.getSystemService(
                Context.DOWNLOAD_SERVICE);
        Uri uri = dm == null ? null : dm.getUriForDownloadedFile(id);
        if (uri == null) {
            UiUtil.toast(activity, activity.getString(R.string.update_download_failed));
            return;
        }
        if (!canInstall()) {
            downloadedId = id;
            new AlertDialog.Builder(activity, R.style.DialogTheme)
                    .setTitle(R.string.update_allow_title)
                    .setMessage(R.string.update_allow_msg)
                    .setPositiveButton(R.string.update_allow_go,
                            (d, w) -> openInstallPermission())
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri("", uri));
        try {
            activity.startActivity(i);
        } catch (Exception e) {
            UiUtil.toast(activity, activity.getString(R.string.update_install_failed));
        }
    }

    private void openInstallPermission() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            UiUtil.toast(activity, activity.getString(R.string.update_install_failed));
        }
    }
}
