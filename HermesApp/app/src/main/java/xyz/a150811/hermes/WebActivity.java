package xyz.a150811.hermes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_ENDPOINT_ID = "endpoint_id";
    public static final String EXTRA_CAPTURE = "capture";
    public static final String EXTRA_LOGIN_BASE = "login_base";

    private static final int REQ_FILE = 41;
    private static final int REQ_PERM = 42;

    private WebView webView;
    private ProgressBar progress;
    private TextView titleView;
    private TextView statusView;
    private TextView errorMsg;
    private TextView captureBanner;
    private View errorOverlay;
    private FrameLayout popupHost;
    private Button retryBtn;
    private Button loginBtn;
    private ImageButton moreBtn;

    private String endpointName = "Hermes";
    private String endpointId;
    private String startUrl;
    private String loginBase;
    private String lastUrl;
    private boolean captureMode;
    private boolean captured;
    private boolean bridgeAdded;
    private boolean tokenDialogShown;
    private boolean skinOn;
    private long pageStart;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private BroadcastReceiver networkReceiver;
    private boolean receiverRegistered;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable netSwitchTask = this::onNetworkSwitch;
    private final Runnable healthTask = this::checkPageHealth;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ExecutorService netPool;
    private int lastTransport = -1;
    private boolean netSwitchPending;
    private int badHealthChecks;
    private int autoReloads;
    private long lastAutoReload;
    private long pausedAt;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);
        UiUtil.applyBars(this);
        if (Prefs.immersive(this)) {
            UiUtil.applyImmersiveNav(this);
        }
        netPool = Executors.newSingleThreadExecutor();
        if (Prefs.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.progress);
        titleView = findViewById(R.id.webTitle);
        statusView = findViewById(R.id.webStatus);
        errorMsg = findViewById(R.id.errorMsg);
        captureBanner = findViewById(R.id.captureBanner);
        errorOverlay = findViewById(R.id.errorOverlay);
        popupHost = findViewById(R.id.popupHost);
        retryBtn = findViewById(R.id.retryBtn);
        loginBtn = findViewById(R.id.loginBtn);
        moreBtn = findViewById(R.id.moreBtn);

        ImageButton back = findViewById(R.id.backBtn);
        ImageButton refresh = findViewById(R.id.refreshBtn);

        setupWebView();

        Intent intent = getIntent();
        String extraName = intent.getStringExtra(EXTRA_NAME);
        if (extraName != null && !extraName.isEmpty()) endpointName = extraName;
        endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID);
        startUrl = intent.getStringExtra(EXTRA_URL);
        loginBase = intent.getStringExtra(EXTRA_LOGIN_BASE);
        captureMode = intent.getBooleanExtra(EXTRA_CAPTURE, false);

        back.setOnClickListener(v -> onBackPressed());
        refresh.setOnClickListener(v -> refresh());
        moreBtn.setOnClickListener(v -> showMenu());
        retryBtn.setOnClickListener(v -> retry());
        loginBtn.setOnClickListener(v -> enterCapture(gatewayBase()));

        titleView.setText(captureMode ? getString(R.string.btn_login_fnos) : endpointName);

        if (savedInstanceState != null && !captureMode) {
            webView.restoreState(savedInstanceState);
        } else if (captureMode) {
            enterCapture(loginBase);
        } else if (startUrl != null) {
            load(startUrl);
        } else {
            showError(getString(R.string.error_default));
        }
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setGeolocationEnabled(false);
        s.setTextZoom(100);
        if (Prefs.desktopUa(this)) {
            s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(getColor(android.R.color.white));
        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new Chrome());
        webView.setDownloadListener(this::handleDownload);
    }

    private void load(String url) {
        if (url == null || url.isEmpty()) {
            showError(getString(R.string.error_default));
            return;
        }
        lastUrl = url;
        tokenDialogShown = false;
        errorOverlay.setVisibility(View.GONE);
        statusView.setText(R.string.web_connecting);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        webView.loadUrl(url);
    }

    private void refresh() {
        if (captureMode) {
            load(loginBase);
            return;
        }
        String url = webView.getUrl();
        if (url == null) url = lastUrl;
        if (url != null) load(url);
    }

    private void retry() {
        errorOverlay.setVisibility(View.GONE);
        if (captureMode) {
            load(loginBase);
        } else {
            load(lastUrl != null ? lastUrl : startUrl);
        }
    }

    private void scheduleHealth(long delay) {
        ui.removeCallbacks(healthTask);
        ui.postDelayed(healthTask, delay);
    }

    private void checkPageHealth() {
        if (isFinishing() || isDestroyed() || captureMode || !resumed) return;
        if (errorOverlay.getVisibility() == View.VISIBLE || webView.getUrl() == null) {
            scheduleHealth(12000);
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        try {
            webView.evaluateJavascript(healthScript(), value -> {
                if (isFinishing() || isDestroyed() || !resumed) return;
                boolean bad = value != null && value.contains("1");
                if (!bad) {
                    badHealthChecks = 0;
                    if (lastAutoReload > 0 && now - lastAutoReload > 300000) autoReloads = 0;
                } else if (++badHealthChecks >= 2) {
                    badHealthChecks = 0;
                    autoReconnect();
                }
                scheduleHealth(bad ? 3000 : 12000);
            });
        } catch (Exception e) {
            scheduleHealth(12000);
        }
    }

    private void autoReconnect() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAutoReload < 15000) return;
        if (autoReloads >= 3) {
            UiUtil.toast(this, getString(R.string.web_auto_reconnect_failed));
            return;
        }
        String url = webView.getUrl();
        if (url == null) url = lastUrl;
        if (url == null) return;
        autoReloads++;
        lastAutoReload = now;
        UiUtil.toast(this, getString(R.string.web_auto_reconnect));
        load(url);
    }

    /**
     * Runs inside the page and returns "1" when the Hermes UI shows a red
     * disconnected badge ("失联" etc.) or the device reports offline.
     */
    private static String healthScript() {
        return "(function(){"
                + "if(navigator.onLine===false)return '1';"
                + "var pat=/^(失联|离线|连接已断开|连接中断)$/;"
                + "var es=document.querySelectorAll('span,div,p,button,li');"
                + "for(var i=0;i<es.length&&i<2000;i++){var el=es[i];"
                + "if(el.children&&el.children.length)continue;"
                + "var t=(el.textContent||'').trim();"
                + "if(t.length>5||!pat.test(t))continue;"
                + "var r=el.getBoundingClientRect();"
                + "if(r.width<=0||r.height<=0)continue;"
                + "var cs=getComputedStyle(el);var fg=cs.color.match(/\\d+/g);"
                + "var bg=cs.backgroundColor.match(/\\d+/g);"
                + "if((fg&&+fg[0]>150&&+fg[1]<110&&+fg[2]<110)||"
                + "(bg&&bg.length>=3&&+bg[0]>150&&+bg[1]<110&&+bg[2]<110))return '1'}"
                + "return '0'})()";
    }

    private void enterCapture(String base) {
        if (base == null || base.isEmpty()) {
            base = baseFromUrl(webView.getUrl() != null ? webView.getUrl() : lastUrl);
        }
        if (base == null || base.isEmpty()) {
            UiUtil.toast(this, getString(R.string.toast_no_line));
            return;
        }
        captureMode = true;
        captured = false;
        ui.removeCallbacks(healthTask);
        loginBase = base;
        captureBanner.setVisibility(View.VISIBLE);
        errorOverlay.setVisibility(View.GONE);
        titleView.setText(R.string.btn_login_fnos);
        statusView.setText(R.string.capture_banner);
        if (!bridgeAdded) {
            webView.addJavascriptInterface(new CaptureBridge(), "HermesCapture");
            bridgeAdded = true;
        }
        load(base);
    }

    private void injectCaptureScript() {
        String js = "(function(){if(window.__hermesCap)return;window.__hermesCap=true;"
                + "setInterval(function(){var a=[];try{a.push(location.href)}catch(e){}"
                + "var f=document.querySelectorAll('iframe');"
                + "for(var i=0;i<f.length;i++){if(f[i].src)a.push(f[i].src)}"
                + "for(var j=0;j<a.length;j++){var u=a[j];"
                + "if(u.indexOf('/app/')>=0&&u.toLowerCase().indexOf('hermes')>=0){"
                + "window.HermesCapture.found(u);return}}},1200);})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean isHermesAppUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("/app/") && u.contains("hermes");
    }

    private static boolean isLiteralHost(String host) {
        return host != null && host.indexOf(':') >= 0;
    }

    private static int portOf(Uri u) {
        int p = u.getPort();
        if (p > 0) return p;
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    /**
     * A page loaded through an IPv6 literal (DNS bypass) may link back to its
     * domain name. Keep such navigation on the same server instead of handing
     * it to the browser, where the phone's DNS would fail again.
     */
    private String adaptHost(String targetUrl) {
        Uri t = Uri.parse(targetUrl);
        String scheme = t.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;

        String cur = webView.getUrl();
        if (cur == null) cur = lastUrl;
        if (cur == null) return null;
        Uri c = Uri.parse(cur);

        String th = t.getHost();
        String ch = c.getHost();
        if (th == null || ch == null || th.equalsIgnoreCase(ch)) return null;
        if (!isLiteralHost(th) && !isLiteralHost(ch)) return null;
        if (!scheme.equalsIgnoreCase(c.getScheme())) return null;
        if (portOf(t) != portOf(c)) return null;

        String authority = c.getAuthority();
        if (authority == null) return null;
        return t.buildUpon().encodedAuthority(authority).build().toString();
    }

    private void onCaptured(String url) {
        if (!captureMode || captured || url == null) return;
        if (!isHermesAppUrl(url)) return;
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return;
        captured = true;

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.capture_found_title)
                .setMessage(getString(R.string.capture_found_msg, url))
                .setPositiveButton(R.string.capture_save, (d, w) -> saveCaptured(url))
                .setNegativeButton(R.string.capture_continue, (d, w) -> captured = false)
                .setOnCancelListener(d -> captured = false)
                .show();
    }

    private void saveCaptured(String url) {
        Endpoint ep = new Endpoint("c" + System.currentTimeMillis(),
                "Hermes · 已捕获", url, Endpoint.KIND_DIRECT);
        EndpointStore.upsert(this, ep);
        Prefs.setFnosSession(this, true);
        captureMode = false;
        captureBanner.setVisibility(View.GONE);
        errorOverlay.setVisibility(View.GONE);
        titleView.setText(endpointName);
        UiUtil.toast(this, getString(R.string.capture_saved));
        load(url);
    }

    private void showMenu() {
        PopupMenu pm = new PopupMenu(this, moreBtn);
        pm.getMenu().add(0, 1, 0, R.string.menu_refresh);
        pm.getMenu().add(0, 2, 0, R.string.menu_switch);
        pm.getMenu().add(0, 3, 0, R.string.menu_browser);
        pm.getMenu().add(0, 4, 0, R.string.menu_login);
        pm.getMenu().add(0, 5, 0, R.string.menu_settings);
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    refresh();
                    return true;
                case 2:
                    showLines();
                    return true;
                case 3:
                    String u = webView.getUrl() != null ? webView.getUrl() : lastUrl;
                    if (u != null) openExternal(Uri.parse(u));
                    return true;
                case 4:
                    enterCapture(gatewayBase());
                    return true;
                case 5:
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                default:
                    return false;
            }
        });
        pm.show();
    }

    private void showLines() {
        final List<Endpoint> eps = EndpointStore.load(this);
        if (eps.isEmpty()) {
            UiUtil.toast(this, getString(R.string.switch_empty));
            return;
        }
        String[] names = new String[eps.size()];
        for (int i = 0; i < eps.size(); i++) {
            names[i] = eps.get(i).name;
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.menu_switch)
                .setItems(names, (d, which) -> {
                    Endpoint e = eps.get(which);
                    endpointName = e.name;
                    endpointId = e.id;
                    titleView.setText(e.name);
                    if (e.kind == Endpoint.KIND_GATEWAY
                            && (e.path == null || e.path.isEmpty())) {
                        UiUtil.toast(this, getString(R.string.switch_need_login));
                        enterCapture(e.loginUrl());
                    } else {
                        load(e.launchUrl());
                    }
                })
                .show();
    }

    private String gatewayBase() {
        for (Endpoint e : EndpointStore.load(this)) {
            if (e.kind == Endpoint.KIND_GATEWAY) return e.loginUrl();
        }
        return baseFromUrl(webView.getUrl() != null ? webView.getUrl() : lastUrl);
    }

    private String baseFromUrl(String url) {
        if (url == null) return null;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            String auth = u.getAuthority();
            if (scheme == null || auth == null) return null;
            return scheme + "://" + auth + "/";
        } catch (Exception e) {
            return null;
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            UiUtil.toast(this, getString(R.string.toast_no_browser));
        }
    }

    private void handleDownload(String url, String userAgent, String contentDisposition,
                                String mimetype, long contentLength) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                UiUtil.toast(this, getString(R.string.toast_download_failed));
                return;
            }
            String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                req.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                req.addRequestHeader("User-Agent", userAgent);
            }
            if (mimetype != null && !mimetype.isEmpty()) {
                req.setMimeType(mimetype);
            }
            req.setTitle(name);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                UiUtil.toast(this, getString(R.string.toast_download, name));
            }
        } catch (Exception e) {
            UiUtil.toast(this, getString(R.string.toast_download_failed));
        }
    }

    private void showError(String message) {
        if (captureMode) {
            captureBanner.setVisibility(View.VISIBLE);
        }
        errorMsg.setText(message == null || message.isEmpty()
                ? getString(R.string.error_default) : message);
        errorOverlay.setVisibility(View.VISIBLE);
        statusView.setText(R.string.web_failed);
        progress.setVisibility(View.GONE);
        loginBtn.setVisibility(captureMode ? View.GONE : View.VISIBLE);
    }

    private void applyChatSkin() {
        if (webView == null || captureMode) return;
        skinOn = Prefs.wechatSkin(this);
        try {
            webView.evaluateJavascript(
                    skinOn ? ChatSkin.applyScript() : ChatSkin.removeScript(), null);
        } catch (Exception ignored) {
        }
    }

    private void checkInvalidToken() {
        if (tokenDialogShown) return;
        webView.evaluateJavascript(
                "(document.body?document.body.innerText:'').slice(0,400)",
                value -> {
                    if (value == null || isFinishing()) return;
                    if (value.toLowerCase(Locale.ROOT).contains("invalid token")) {
                        tokenDialogShown = true;
                        statusView.setText(R.string.status_login);
                        new AlertDialog.Builder(WebActivity.this, R.style.DialogTheme)
                                .setTitle(R.string.token_expired_title)
                                .setMessage(R.string.token_expired_msg)
                                .setPositiveButton(R.string.btn_login_fnos,
                                        (d, w) -> enterCapture(gatewayBase()))
                                .setNegativeButton(R.string.dialog_cancel, null)
                                .show();
                    }
                });
    }

    private void handlePermission(PermissionRequest request) {
        List<String> missing = new ArrayList<>();
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.RECORD_AUDIO);
            }
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.CAMERA);
            }
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        pendingPermission = request;
        requestPermissions(missing.toArray(new String[0]), REQ_PERM);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String adapted = adaptHost(uri.toString());
            String u = adapted != null ? adapted : uri.toString();
            if (captureMode && isHermesAppUrl(u)) {
                onCaptured(u);
                return true;
            }
            if (adapted != null) {
                view.loadUrl(adapted);
                return true;
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("http") || scheme.equals("https")) {
                String current = view.getUrl();
                String h1 = uri.getHost();
                String h2 = current == null ? null : Uri.parse(current).getHost();
                if (h1 != null && h2 != null && h1.equalsIgnoreCase(h2)) {
                    return false;
                }
                openExternal(uri);
                return true;
            }
            openExternal(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageStart = SystemClock.elapsedRealtime();
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(0);
            statusView.setText(R.string.web_connecting);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (isFinishing()) return;
            progress.setVisibility(View.GONE);
            lastUrl = url;
            if (captureMode) {
                injectCaptureScript();
                statusView.setText(R.string.capture_banner);
                return;
            }
            long ms = pageStart > 0 ? SystemClock.elapsedRealtime() - pageStart : 0;
            statusView.setText(getString(R.string.web_connected, ms));
            badHealthChecks = 0;
            checkInvalidToken();
            applyChatSkin();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) {
                showError(error.getDescription() + " (" + error.getErrorCode() + ")");
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                showError("HTTP " + response.getStatusCode());
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showError(getString(R.string.toast_ssl_blocked) + "\n#" + error.getPrimaryError());
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = callback;
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                String[] accept = params.getAcceptTypes();
                if (accept != null && accept.length > 0
                        && accept[0] != null && !accept[0].trim().isEmpty()
                        && !accept[0].equals("*/*")) {
                    if (accept[0].contains("/")) {
                        i.setType(accept[0]);
                    }
                    i.putExtra(Intent.EXTRA_MIME_TYPES, accept);
                }
                startActivityForResult(Intent.createChooser(i, getString(R.string.choose_file)),
                        REQ_FILE);
                return true;
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> handlePermission(request));
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            WebView temp = new WebView(WebActivity.this);
            WebSettings ts = temp.getSettings();
            ts.setJavaScriptEnabled(true);
            ts.setDomStorageEnabled(true);
            ts.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            ts.setUserAgentString(webView.getSettings().getUserAgentString());
            temp.setBackgroundColor(Color.WHITE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(temp, true);
            temp.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    String adapted = adaptHost(uri.toString());
                    String u = adapted != null ? adapted : uri.toString();
                    if (captureMode && isHermesAppUrl(u)) {
                        closePopup();
                        onCaptured(u);
                        return true;
                    }
                    if (adapted != null) {
                        v.loadUrl(adapted);
                        return true;
                    }
                    String scheme = uri.getScheme() == null ? ""
                            : uri.getScheme().toLowerCase(Locale.ROOT);
                    if (scheme.equals("http") || scheme.equals("https")) {
                        String h1 = uri.getHost();
                        String h2 = lastUrl == null ? null : Uri.parse(lastUrl).getHost();
                        if (h1 != null && h2 != null && h1.equalsIgnoreCase(h2)) return false;
                    }
                    openExternal(uri);
                    closePopup();
                    return true;
                }

                @Override
                public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                    handler.cancel();
                }
            });
            temp.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onCloseWindow(WebView window) {
                    closePopup();
                }
            });
            popupHost.removeAllViews();
            popupHost.addView(temp, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            popupHost.setVisibility(View.VISIBLE);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(temp);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(WebActivity.this, R.style.DialogTheme)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                                   final JsResult result) {
            new AlertDialog.Builder(WebActivity.this, R.style.DialogTheme)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  final JsPromptResult result) {
            EditText input = new EditText(WebActivity.this);
            input.setText(defaultValue == null ? "" : defaultValue);
            new AlertDialog.Builder(WebActivity.this, R.style.DialogTheme)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok,
                            (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }
    }

    private class CaptureBridge {
        @JavascriptInterface
        public void found(final String url) {
            runOnUiThread(() -> onCaptured(url));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM && pendingPermission != null) {
            boolean ok = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            }
            if (ok) {
                pendingPermission.grant(pendingPermission.getResources());
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    private void registerNetwork() {
        if (receiverRegistered) return;
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isOnline() && errorOverlay.getVisibility() == View.VISIBLE) {
                    retryBtn.postDelayed(WebActivity.this::retry, 500);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(networkReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(networkReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
        }

        if (networkCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        handleTransport(network);
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        handleTransport(network);
                    }
                };
                try {
                    cm.registerDefaultNetworkCallback(networkCallback);
                } catch (Exception e) {
                    networkCallback = null;
                }
            }
        }
    }

    private void unregisterNetwork() {
        if (networkCallback != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        ui.removeCallbacks(netSwitchTask);
        if (receiverRegistered && networkReceiver != null) {
            try {
                unregisterReceiver(networkReceiver);
            } catch (Exception ignored) {
            }
        }
        receiverRegistered = false;
    }

    private void handleTransport(Network network) {
        if (isFinishing() || isDestroyed()) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(network);
        if (caps == null) return;
        final int transport;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transport = 1;
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transport = 2;
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transport = 3;
        } else {
            return;
        }
        ui.post(() -> {
            int prev = lastTransport;
            lastTransport = transport;
            if (prev == -1 || prev == transport) return;
            ui.removeCallbacks(netSwitchTask);
            ui.postDelayed(netSwitchTask, 1500);
        });
    }

    /** After a Wi-Fi/cellular hand-off the old socket usually stays dead; reconnect. */
    private void onNetworkSwitch() {
        if (isFinishing() || isDestroyed() || netSwitchPending) return;

        if (captureMode) {
            if (loginBase != null) {
                statusView.setText(R.string.web_network_switch);
                load(loginBase);
            }
            return;
        }

        final String currentUrl = webView.getUrl() != null ? webView.getUrl() : lastUrl;
        final Endpoint current = findEndpoint(currentUrl);
        if (current == null) {
            if (currentUrl != null) {
                UiUtil.toast(this, getString(R.string.web_reconnected));
                load(currentUrl);
            }
            return;
        }

        netSwitchPending = true;
        statusView.setText(R.string.web_network_switch);
        progress.setVisibility(View.VISIBLE);
        try {
            netPool.execute(() -> {
                Probe.Result cr = Probe.run(current);
                if (cr.state == Endpoint.STATE_READY
                        || cr.state == Endpoint.STATE_NEEDS_LOGIN) {
                    runOnUiThread(() -> applyReconnect(current, cr, false));
                    return;
                }
                List<Endpoint> all = EndpointStore.load(WebActivity.this);
                Endpoint alt = pickAlternative(all, current);
                if (alt == null) {
                    runOnUiThread(() -> applyReconnect(current, cr, false));
                    return;
                }
                Probe.Result ar = Probe.run(alt);
                runOnUiThread(() -> {
                    boolean usable = ar.state == Endpoint.STATE_READY
                            || ar.state == Endpoint.STATE_NEEDS_LOGIN;
                    if (usable) {
                        applyReconnect(alt, ar, true);
                    } else {
                        applyReconnect(current, cr, false);
                    }
                });
            });
        } catch (Exception e) {
            netSwitchPending = false;
        }
    }

    private void applyReconnect(Endpoint target, Probe.Result r, boolean switched) {
        netSwitchPending = false;
        if (isFinishing() || isDestroyed()) return;
        if (r.base != null && !r.base.isEmpty()) {
            target.resolvedBase = r.base;
        }
        if (target.kind == Endpoint.KIND_GATEWAY && r.state == Endpoint.STATE_READY) {
            String p = Probe.pathOf(r.url);
            if (p != null && p.startsWith("/")) target.path = p;
        }

        if (r.state == Endpoint.STATE_READY || r.state == Endpoint.STATE_NEEDS_LOGIN) {
            target.resolvedUrl = r.url;
            EndpointStore.upsert(this, target);
            endpointId = target.id;
            endpointName = target.name;
            titleView.setText(target.name);
            if (r.state == Endpoint.STATE_NEEDS_LOGIN
                    && target.kind == Endpoint.KIND_GATEWAY && !hasPath(target)) {
                UiUtil.toast(this, getString(R.string.switch_need_login));
                enterCapture(target.loginUrl());
                return;
            }
            String url = r.url != null ? r.url : target.launchUrl();
            UiUtil.toast(this, switched
                    ? getString(R.string.web_switched_line, target.name)
                    : getString(R.string.web_reconnected));
            load(url);
            return;
        }

        String fallback = webView.getUrl() != null ? webView.getUrl() : lastUrl;
        if (fallback != null) {
            load(fallback);
        } else {
            retry();
        }
    }

    private Endpoint findEndpoint(String url) {
        List<Endpoint> list = EndpointStore.load(this);
        if (endpointId != null) {
            for (Endpoint e : list) {
                if (endpointId.equals(e.id)) return e;
            }
        }
        String host = hostOf(url);
        if (host == null) return null;
        for (Endpoint e : list) {
            if (host.equalsIgnoreCase(hostOf(e.url))
                    || host.equalsIgnoreCase(hostOf(e.resolvedBase))) {
                return e;
            }
        }
        return null;
    }

    private Endpoint pickAlternative(List<Endpoint> all, Endpoint current) {
        boolean wifi = lastTransport == 1 || lastTransport == 3;
        Endpoint best = null;
        Endpoint fallback = null;
        for (Endpoint e : all) {
            if (e.id.equals(current.id)) continue;
            if (fallback == null) fallback = e;
            boolean priv = isPrivateHost(hostOf(e.url));
            if (wifi != priv) continue;
            if (best == null || altScore(e) > altScore(best)) best = e;
        }
        return best != null ? best : fallback;
    }

    private static int altScore(Endpoint e) {
        int score = hasPath(e) ? 4 : 0;
        if (e.kind == Endpoint.KIND_DIRECT) score += 2;
        if ("lan-auto".equals(e.id)) score += 1;
        return score;
    }

    private static boolean hasPath(Endpoint e) {
        return e.path != null && !e.path.trim().isEmpty();
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return null;
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPrivateHost(String host) {
        if (host == null) return false;
        if (host.indexOf(':') >= 0) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".local") || h.endsWith(".lan") || h.equals("localhost")) return true;
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 10 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (Prefs.immersive(this)) {
            UiUtil.applyImmersiveNav(this);
        }
        webView.onResume();
        registerNetwork();
        long away = pausedAt > 0 ? SystemClock.elapsedRealtime() - pausedAt : 0;
        pausedAt = 0;
        if (!captureMode) {
            scheduleHealth(away > 15000 ? 800 : 12000);
            if (Prefs.wechatSkin(this) != skinOn) {
                applyChatSkin();
            }
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        pausedAt = SystemClock.elapsedRealtime();
        ui.removeCallbacks(healthTask);
        webView.onPause();
        unregisterNetwork();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (Prefs.immersive(this)) {
            UiUtil.applyImmersiveNav(this);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!captureMode) {
            webView.saveState(outState);
        }
    }

    private void closePopup() {
        if (popupHost == null) return;
        for (int i = 0; i < popupHost.getChildCount(); i++) {
            View child = popupHost.getChildAt(i);
            if (child instanceof WebView) {
                WebView w = (WebView) child;
                w.stopLoading();
                w.setWebChromeClient(null);
                w.setWebViewClient(null);
                w.destroy();
            }
        }
        popupHost.removeAllViews();
        popupHost.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (popupHost != null && popupHost.getVisibility() == View.VISIBLE) {
            closePopup();
            return;
        }
        if (captureMode) {
            finish();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterNetwork();
        ui.removeCallbacksAndMessages(null);
        if (netPool != null) netPool.shutdownNow();
        closePopup();
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("HermesCapture");
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
