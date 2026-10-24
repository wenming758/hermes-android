package xyz.a150811.hermes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends Activity {
    private LinearLayout list;
    private TextView empty;
    private Button scanBtn;
    private Button notifyBtn;
    private Button updateBtn;
    private long downloadId = -1;
    private long downloadedId = -1;
    private BroadcastReceiver downloadReceiver;
    private List<Endpoint> endpoints;
    private boolean changed;
    private boolean scanning;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService scanPool = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        UiUtil.applyBars(this);

        list = findViewById(R.id.endpointList);
        empty = findViewById(R.id.emptyHint);
        ImageButton back = findViewById(R.id.backBtn);
        Button add = findViewById(R.id.addBtn);
        scanBtn = findViewById(R.id.scanBtn);
        Button reset = findViewById(R.id.resetBtn);
        Button clear = findViewById(R.id.clearBtn);
        Switch auto = findViewById(R.id.switchAuto);
        Switch desktop = findViewById(R.id.switchDesktop);
        Switch keepOn = findViewById(R.id.switchKeepOn);
        Switch immersive = findViewById(R.id.switchImmersive);
        notifyBtn = findViewById(R.id.notifyBtn);
        notifyBtn.setOnClickListener(v -> openNotifySettings());
        updateBtn = findViewById(R.id.updateBtn);
        updateBtn.setOnClickListener(v -> checkUpdate());

        auto.setChecked(Prefs.autoConnect(this));
        desktop.setChecked(Prefs.desktopUa(this));
        keepOn.setChecked(Prefs.keepScreenOn(this));
        immersive.setChecked(Prefs.immersive(this));

        auto.setOnCheckedChangeListener((b, v) -> {
            Prefs.setAutoConnect(this, v);
            changed = true;
        });
        desktop.setOnCheckedChangeListener((b, v) -> {
            Prefs.setDesktopUa(this, v);
            changed = true;
        });
        keepOn.setOnCheckedChangeListener((b, v) -> {
            Prefs.setKeepScreenOn(this, v);
            changed = true;
        });
        immersive.setOnCheckedChangeListener((b, v) -> {
            Prefs.setImmersive(this, v);
            changed = true;
        });

        back.setOnClickListener(v -> finishBack());
        add.setOnClickListener(v -> showDialog(null));
        scanBtn.setOnClickListener(v -> scanLan());
        reset.setOnClickListener(v -> confirm(getString(R.string.settings_reset_confirm), () -> {
            EndpointStore.save(this, new ArrayList<Endpoint>());
            Prefs.setSelectedLine(this, "");
            endpoints = EndpointStore.load(this);
            render();
            changed = true;
        }));
        clear.setOnClickListener(v -> confirm(getString(R.string.settings_clear_confirm), () -> {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            Prefs.setFnosSession(this, false);
            UiUtil.toast(this, getString(R.string.settings_clear_done));
        }));

        TextView about = findViewById(R.id.aboutBody);
        about.setText(getString(R.string.settings_about_body, BuildConfig.VERSION_NAME));

        endpoints = EndpointStore.load(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNotifyLabel();
        if (downloadedId != -1 && canInstallPackages()) {
            long id = downloadedId;
            downloadedId = -1;
            installDownloaded(id);
        }
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls();
    }

    private void checkUpdate() {
        updateBtn.setEnabled(false);
        updateBtn.setText(R.string.update_checking);
        UpdateChecker.check(this, (release, error) -> {
            if (isFinishing() || isDestroyed()) return;
            updateBtn.setEnabled(true);
            updateBtn.setText(R.string.update_check);
            if (error != null) {
                UiUtil.toast(this, getString(R.string.update_failed, error));
                return;
            }
            if (release == null) {
                UiUtil.toast(this, getString(R.string.update_latest, BuildConfig.VERSION_NAME));
                return;
            }
            showUpdateDialog(release);
        });
    }

    private void showUpdateDialog(UpdateChecker.Release release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.isEmpty()) notes = getString(R.string.update_notes_empty);
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        notes += "\n\n更新源：github.com/wenming758/hermes-android";
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(getString(R.string.update_found_title, release.version))
                .setMessage(notes)
                .setPositiveButton(R.string.update_download,
                        (d, w) -> startDownload(release))
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
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                    "Hermes-" + release.version + ".apk");
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("no download manager");
            downloadId = dm.enqueue(req);
            registerDownloadReceiver();
            UiUtil.toast(this, getString(R.string.update_downloading));
        } catch (Exception e) {
            UiUtil.toast(this, getString(R.string.update_download_failed));
        }
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) installDownloaded(id);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(downloadReceiver, filter);
            }
        } catch (Exception e) {
            downloadReceiver = null;
        }
    }

    private void unregisterDownloadReceiver() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
    }

    private void installDownloaded(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        Uri uri = dm == null ? null : dm.getUriForDownloadedFile(id);
        if (uri == null) {
            UiUtil.toast(this, getString(R.string.update_download_failed));
            return;
        }
        if (!canInstallPackages()) {
            downloadedId = id;
            new AlertDialog.Builder(this, R.style.DialogTheme)
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
            startActivity(i);
        } catch (Exception e) {
            UiUtil.toast(this, getString(R.string.update_install_failed));
        }
    }

    private void openInstallPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            UiUtil.toast(this, getString(R.string.update_install_failed));
        }
    }

    private void updateNotifyLabel() {
        notifyBtn.setText(notifyEnabled()
                ? R.string.settings_notify_on : R.string.settings_notify_off);
    }

    private boolean notifyEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    private void openNotifySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                UiUtil.toast(this, getString(R.string.settings_notify_failed));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateNotifyLabel();
    }

    private void render() {
        list.removeAllViews();
        empty.setVisibility(endpoints.isEmpty() ? View.VISIBLE : View.GONE);
        for (Endpoint e : endpoints) {
            View row = getLayoutInflater().inflate(R.layout.item_endpoint_edit, list, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UiUtil.dp(this, 8);
            row.setLayoutParams(lp);

            ((TextView) row.findViewById(R.id.name)).setText(e.name);
            ((TextView) row.findViewById(R.id.url)).setText(e.url);
            row.setOnClickListener(v -> showDialog(e));
            row.setOnLongClickListener(v -> {
                confirmDelete(e);
                return true;
            });
            row.findViewById(R.id.editBtn).setOnClickListener(v -> showDialog(e));
            row.findViewById(R.id.deleteBtn).setOnClickListener(v -> confirmDelete(e));
            list.addView(row);
        }
    }

    private void showDialog(Endpoint existing) {
        View content = getLayoutInflater().inflate(R.layout.dialog_endpoint, null);
        EditText nameInput = content.findViewById(R.id.nameInput);
        EditText urlInput = content.findViewById(R.id.urlInput);
        if (existing != null) {
            nameInput.setText(existing.name);
            urlInput.setText(existing.url);
        }

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(existing == null ? R.string.dialog_add_title : R.string.dialog_edit_title)
                .setView(content)
                .setPositiveButton(R.string.dialog_save, null)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                String url = normalize(urlInput.getText().toString());
                if (url == null) {
                    urlInput.setError(getString(R.string.dialog_url_invalid));
                    return;
                }
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    name = hostOf(url);
                }
                if (existing == null) {
                    Endpoint ep = new Endpoint("c" + System.currentTimeMillis(),
                            name, url, kindFor(url));
                    EndpointStore.upsert(this, ep);
                } else {
                    existing.name = name;
                    existing.url = url;
                    existing.kind = kindFor(url);
                    existing.path = "";
                    EndpointStore.save(this, endpoints);
                }
                endpoints = EndpointStore.load(this);
                render();
                changed = true;
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void scanLan() {
        if (scanning) return;
        scanning = true;
        scanBtn.setEnabled(false);
        scanBtn.setText(R.string.scanning_short);
        scanPool.execute(() -> {
            List<String> found = Probe.scanLan();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                scanning = false;
                scanBtn.setEnabled(true);
                scanBtn.setText(R.string.scan_lan);
                boolean added = !found.isEmpty() && EndpointStore.mergeLan(this, found.get(0));
                if (added) {
                    endpoints = EndpointStore.load(this);
                    render();
                    changed = true;
                    UiUtil.toast(this, getString(R.string.scan_added, found.get(0)));
                } else {
                    UiUtil.toast(this, found.isEmpty()
                            ? getString(R.string.scan_none) : getString(R.string.scan_exists));
                }
            });
        });
    }

    private void confirmDelete(Endpoint e) {
        confirm(getString(R.string.dialog_delete_confirm), () -> {
            EndpointStore.delete(this, e);
            if (e.id.equals(Prefs.selectedLine(this))) Prefs.setSelectedLine(this, "");
            endpoints = EndpointStore.load(this);
            render();
            changed = true;
        });
    }

    private void confirm(String message, Runnable onOk) {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setMessage(message)
                .setPositiveButton(R.string.dialog_save, (d, w) -> onOk.run())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "http://" + s;
        Uri u = Uri.parse(s);
        String scheme = u.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        if (u.getHost() == null || u.getHost().isEmpty()) return null;
        return s;
    }

    private static int kindFor(String url) {
        Uri u = Uri.parse(url);
        String path = u.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return Endpoint.KIND_GATEWAY;
        }
        return Endpoint.KIND_DIRECT;
    }

    private static String hostOf(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            return host == null || host.isEmpty() ? "Hermes" : host;
        } catch (Exception e) {
            return "Hermes";
        }
    }

    private void finishBack() {
        setResult(changed ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishBack();
    }

    @Override
    protected void onDestroy() {
        unregisterDownloadReceiver();
        ui.removeCallbacksAndMessages(null);
        scanPool.shutdownNow();
        super.onDestroy();
    }
}
