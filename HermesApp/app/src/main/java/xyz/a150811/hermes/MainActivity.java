package xyz.a150811.hermes;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_SETTINGS = 100;
    private static final int REQ_WEB = 101;
    private static final int REQ_NOTIFY = 102;

    private LinearLayout endpointList;
    private TextView statusTitle;
    private TextView statusSub;
    private TextView footer;
    private View statusDot;
    private ProgressBar statusProgress;
    private Button primaryBtn;
    private Button rescanBtn;
    private Button settingsBtn;
    private Button updateBtn;
    private Button linesTopBtn;
    private UpdateFlow updateFlow;
    private TextView lineCount;
    private View strategyBtn;
    private TextView strategyName;
    private ImageView robot;
    private ImageView sparkleA;
    private ImageView sparkleB;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private ExecutorService scanPool;
    private List<Endpoint> endpoints = new ArrayList<>();
    private Endpoint bestReady;
    private Endpoint bestLogin;
    private int pending;
    private boolean probing;
    private boolean scanning;
    private boolean pendingReprobe;
    private boolean autoOpened;
    private boolean showAdd;
    private String pendingOpenId;
    private Runnable autoOpenRunnable;
    private ObjectAnimator bob;
    private ObjectAnimator twinkleA;
    private ObjectAnimator twinkleB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        UiUtil.applyBars(this);

        pool = Executors.newFixedThreadPool(6);
        scanPool = Executors.newSingleThreadExecutor();
        endpointList = findViewById(R.id.endpointList);
        statusTitle = findViewById(R.id.statusTitle);
        statusSub = findViewById(R.id.statusSub);
        statusDot = findViewById(R.id.statusDot);
        statusProgress = findViewById(R.id.statusProgress);
        primaryBtn = findViewById(R.id.primaryBtn);
        rescanBtn = findViewById(R.id.rescanBtn);
        settingsBtn = findViewById(R.id.settingsBtn);
        updateBtn = findViewById(R.id.updateBtn);
        updateFlow = new UpdateFlow(this);
        updateFlow.bind(updateBtn);
        linesTopBtn = findViewById(R.id.linesTopBtn);
        lineCount = findViewById(R.id.lineCount);
        strategyBtn = findViewById(R.id.strategyBtn);
        strategyName = findViewById(R.id.strategyName);
        footer = findViewById(R.id.footer);
        robot = findViewById(R.id.robot);
        sparkleA = findViewById(R.id.sparkleA);
        sparkleB = findViewById(R.id.sparkleB);

        robot.setTranslationY(0f);
        bob = ObjectAnimator.ofFloat(robot, "translationY", 0f, -UiUtil.dp(this, 7));
        bob.setDuration(1600);
        bob.setRepeatMode(ValueAnimator.REVERSE);
        bob.setRepeatCount(ValueAnimator.INFINITE);
        bob.setInterpolator(new AccelerateDecelerateInterpolator());
        bob.start();

        twinkleA = ObjectAnimator.ofFloat(sparkleA, "alpha", 0.25f, 1f);
        twinkleA.setDuration(1100);
        twinkleA.setRepeatMode(ValueAnimator.REVERSE);
        twinkleA.setRepeatCount(ValueAnimator.INFINITE);
        twinkleA.start();
        twinkleB = ObjectAnimator.ofFloat(sparkleB, "alpha", 0.2f, 0.9f);
        twinkleB.setDuration(1500);
        twinkleB.setRepeatMode(ValueAnimator.REVERSE);
        twinkleB.setRepeatCount(ValueAnimator.INFINITE);
        twinkleB.setStartDelay(400);
        twinkleB.start();

        View content = findViewById(android.R.id.content);
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(260).start();

        primaryBtn.setOnClickListener(v -> {
            Endpoint selected = selectedEndpoint();
            if (showAdd && selected == null) {
                openSettings();
                return;
            }
            if (selected != null) {
                if (selected.state == Endpoint.STATE_READY) {
                    openEndpoint(selected);
                } else if (selected.state == Endpoint.STATE_NEEDS_LOGIN) {
                    startLogin(selected);
                } else if (!probing) {
                    pendingOpenId = selected.id;
                    probeAll();
                }
                return;
            }
            if (bestReady != null) {
                openEndpoint(bestReady);
            } else if (bestLogin != null) {
                startLogin(bestLogin);
            } else {
                probeAll();
            }
        });
        rescanBtn.setOnClickListener(v -> {
            probeAll();
            scanLan(false);
        });
        settingsBtn.setOnClickListener(v -> openSettings());
        linesTopBtn.setOnClickListener(v -> openSettings());
        strategyBtn.setOnClickListener(v -> showStrategyDialog());

        endpoints = EndpointStore.load(this);
        reconcileSelection();
        footer.setText(getString(R.string.footer_format, BuildConfig.VERSION_NAME));
        render();
        if (endpoints.isEmpty()) {
            showEmptyState();
            scanLan(false);
        } else {
            probeAll();
            scanLan(false);
        }
        askNotifyPermission();
    }

    private void askNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        if (Prefs.notifyAsked(this)) return;
        Prefs.setNotifyAsked(this, true);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    private Endpoint selectedEndpoint() {
        String id = Prefs.selectedLine(this);
        if (id == null || id.isEmpty()) return null;
        for (Endpoint e : endpoints) {
            if (id.equals(e.id)) return e;
        }
        return null;
    }

    private Endpoint findById(String id) {
        if (id == null) return null;
        for (Endpoint e : endpoints) {
            if (id.equals(e.id)) return e;
        }
        return null;
    }

    private void reconcileSelection() {
        String id = Prefs.selectedLine(this);
        if (id == null || id.isEmpty()) return;
        if (findById(id) == null) Prefs.setSelectedLine(this, "");
    }

    private void showStrategyDialog() {
        final List<Endpoint> list = new ArrayList<>(endpoints);
        String[] names = new String[list.size() + 1];
        names[0] = getString(R.string.strategy_auto);
        String current = Prefs.selectedLine(this);
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            names[i + 1] = list.get(i).name;
            if (list.get(i).id.equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.strategy_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    String label;
                    if (which == 0) {
                        Prefs.setSelectedLine(this, "");
                        label = getString(R.string.strategy_auto_short);
                    } else {
                        Endpoint e = list.get(which - 1);
                        Prefs.setSelectedLine(this, e.id);
                        label = e.name;
                    }
                    dialog.dismiss();
                    updateStrategyLabel();
                    updatePrimaryForSelection();
                    UiUtil.toast(this, getString(R.string.strategy_changed, label));
                })
                .show();
    }

    private void updateStrategyLabel() {
        Endpoint selected = selectedEndpoint();
        strategyName.setText(selected == null
                ? getString(R.string.strategy_auto_short) : selected.name);
    }

    private void updatePrimaryForSelection() {
        if (probing) return;
        Endpoint selected = selectedEndpoint();
        if (selected == null) return;
        primaryBtn.setVisibility(View.VISIBLE);
        if (selected.state == Endpoint.STATE_READY) {
            primaryBtn.setText(R.string.btn_enter);
        } else if (selected.state == Endpoint.STATE_NEEDS_LOGIN) {
            primaryBtn.setText(R.string.btn_login_fnos);
        } else {
            primaryBtn.setText(R.string.btn_retry_line);
        }
    }

    private void showEmptyState() {
        showAdd = true;
        bestReady = null;
        bestLogin = null;
        setStatus(getString(R.string.status_no_lines), getString(R.string.status_no_lines_sub),
                R.drawable.bg_dot_warn, scanning);
        primaryBtn.setText(R.string.btn_add_line);
        primaryBtn.setVisibility(View.VISIBLE);
        updateActionLabels(true);
    }

    private void updateActionLabels(boolean empty) {
        rescanBtn.setText(empty ? R.string.btn_scan_lan : R.string.btn_rescan);
    }

    private void scanLan(boolean manual) {
        if (scanning) return;
        scanning = true;
        if (endpoints.isEmpty()) showEmptyState();
        if (manual) UiUtil.toast(this, getString(R.string.scanning_lan));
        scanPool.execute(() -> {
            List<String> found = Probe.scanLan();
            ui.post(() -> onScanDone(found, manual));
        });
    }

    private void onScanDone(List<String> found, boolean manual) {
        if (isFinishing() || isDestroyed()) return;
        scanning = false;
        String base = found.isEmpty() ? null : found.get(0);
        boolean changed = base != null && EndpointStore.mergeLan(this, base);
        if (changed) {
            endpoints = EndpointStore.load(this);
            reconcileSelection();
            UiUtil.toast(this, getString(R.string.scan_added, base));
            if (probing) {
                pendingReprobe = true;
            } else {
                render();
                probeAll();
            }
            return;
        }
        if (manual) {
            UiUtil.toast(this, found.isEmpty()
                    ? getString(R.string.scan_none) : getString(R.string.scan_exists));
        }
        if (endpoints.isEmpty()) {
            showEmptyState();
        } else if (!probing) {
            render();
        }
    }

    private void probeAll() {
        if (probing) return;
        if (endpoints.isEmpty()) {
            showEmptyState();
            return;
        }
        probing = true;
        autoOpened = false;
        showAdd = false;
        bestReady = null;
        bestLogin = null;
        for (Endpoint e : endpoints) {
            e.state = Endpoint.STATE_PROBING;
            e.latency = -1;
            e.detail = "";
            e.resolvedUrl = null;
        }
        render();
        setStatus(getString(R.string.status_scanning), getString(R.string.status_scanning_sub),
                R.drawable.bg_dot_probing, true);
        primaryBtn.setVisibility(View.GONE);

        List<Endpoint> snapshot = new ArrayList<>(endpoints);
        pending = snapshot.size();
        for (Endpoint e : snapshot) {
            try {
                pool.execute(() -> {
                    Probe.Result result = Probe.run(e);
                    ui.post(() -> onProbeDone(e, result));
                });
            } catch (Exception ex) {
                pending--;
            }
        }
        if (pending == 0) {
            finishProbe();
        }
    }

    private void onProbeDone(Endpoint e, Probe.Result r) {
        if (isFinishing() || isDestroyed()) return;
        e.state = r.state;
        e.latency = r.ms;
        e.detail = r.detail == null ? "" : r.detail;
        e.resolvedUrl = r.url;
        if (r.base != null && !r.base.isEmpty()) {
            e.resolvedBase = r.base;
        }
        if (e.kind == Endpoint.KIND_GATEWAY && r.state == Endpoint.STATE_READY) {
            String p = Probe.pathOf(r.url);
            if (p != null && p.startsWith("/")) {
                e.path = p;
            }
        }
        if (r.state == Endpoint.STATE_READY
                && (bestReady == null || (r.ms >= 0 && r.ms < bestReady.latency))) {
            bestReady = e;
        }
        if (r.state == Endpoint.STATE_NEEDS_LOGIN
                && (bestLogin == null || (r.ms >= 0 && r.ms < bestLogin.latency))) {
            bestLogin = e;
        }
        pending--;
        render();
        if (pending <= 0) {
            finishProbe();
        }
    }

    private void finishProbe() {
        probing = false;
        EndpointStore.save(this, endpoints);
        render();
        if (pendingReprobe) {
            pendingReprobe = false;
            probeAll();
            return;
        }
        Endpoint pendingTarget = findById(pendingOpenId);
        pendingOpenId = null;
        if (pendingTarget != null && pendingTarget.state == Endpoint.STATE_READY) {
            openEndpoint(pendingTarget);
            return;
        }
        if (pendingTarget != null && pendingTarget.state == Endpoint.STATE_NEEDS_LOGIN) {
            startLogin(pendingTarget);
            return;
        }
        if (endpoints.isEmpty()) {
            showEmptyState();
            return;
        }
        if (bestReady != null) {
            showAdd = false;
            setStatus(getString(R.string.status_ready),
                    bestReady.name + " · " + bestReady.latency + " ms",
                    R.drawable.bg_dot_ready, false);
            primaryBtn.setText(R.string.btn_enter);
            primaryBtn.setVisibility(View.VISIBLE);
            if (Prefs.autoConnect(this) && !autoOpened) {
                autoOpened = true;
                Endpoint chosen = selectedEndpoint();
                final Endpoint autoTarget = chosen != null ? chosen : bestReady;
                autoOpenRunnable = () -> {
                    if (isFinishing()) return;
                    autoOpenRunnable = null;
                    if (autoTarget.state == Endpoint.STATE_READY) {
                        openEndpoint(autoTarget);
                    } else if (autoTarget.state == Endpoint.STATE_NEEDS_LOGIN) {
                        startLogin(autoTarget);
                    }
                };
                ui.postDelayed(autoOpenRunnable, 700);
            }
        } else if (bestLogin != null) {
            showAdd = false;
            setStatus(getString(R.string.status_login), getString(R.string.status_login_sub),
                    R.drawable.bg_dot_warn, false);
            primaryBtn.setText(R.string.btn_login_fnos);
            primaryBtn.setVisibility(View.VISIBLE);
        } else {
            String sub = getString(R.string.status_fail_sub);
            if (!Probe.hasGlobalIpv6()) {
                sub = getString(R.string.status_fail_no_ipv6);
            }
            setStatus(getString(R.string.status_fail), sub,
                    R.drawable.bg_dot_fail, false);
            showAdd = true;
            primaryBtn.setText(R.string.btn_add_line);
            primaryBtn.setVisibility(View.VISIBLE);
        }
        updatePrimaryForSelection();
    }

    private void cancelAutoOpen() {
        if (autoOpenRunnable != null) {
            ui.removeCallbacks(autoOpenRunnable);
            autoOpenRunnable = null;
        }
    }

    private void setStatus(String title, String sub, int dotRes, boolean progressVisible) {
        statusTitle.setText(title);
        statusSub.setText(sub);
        statusDot.setBackgroundResource(dotRes);
        statusProgress.setVisibility(progressVisible ? View.VISIBLE : View.GONE);
    }

    private void render() {
        endpointList.removeAllViews();
        lineCount.setText(getString(R.string.lines_count, endpoints.size()));
        updateActionLabels(endpoints.isEmpty());
        updateStrategyLabel();
        Endpoint selected = selectedEndpoint();
        for (Endpoint e : endpoints) {
            View row = getLayoutInflater().inflate(R.layout.item_endpoint, endpointList, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UiUtil.dp(this, 10);
            row.setLayoutParams(lp);

            View dot = row.findViewById(R.id.dot);
            TextView name = row.findViewById(R.id.name);
            TextView url = row.findViewById(R.id.url);
            TextView latency = row.findViewById(R.id.latency);
            TextView state = row.findViewById(R.id.state);

            name.setText(e.name);
            url.setText(displayUrl(e));
            latency.setText(latencyText(e));
            state.setText(stateText(e));
            dot.setBackgroundResource(dotRes(e.state));
            latency.setBackgroundResource(latencyPill(e));
            latency.setPadding(UiUtil.dp(this, 8), UiUtil.dp(this, 2),
                    UiUtil.dp(this, 8), UiUtil.dp(this, 2));

            if (e.state == Endpoint.STATE_READY) {
                if (e.latency < 120) {
                    latency.setTextColor(getColor(R.color.green));
                } else if (e.latency < 400) {
                    latency.setTextColor(getColor(R.color.amber));
                } else {
                    latency.setTextColor(getColor(R.color.red));
                }
            } else if (e.state == Endpoint.STATE_NEEDS_LOGIN) {
                latency.setTextColor(getColor(R.color.amber));
            } else {
                latency.setTextColor(getColor(R.color.ink_soft));
            }

            boolean best = e == bestReady && e.state == Endpoint.STATE_READY;
            row.setBackgroundResource(e == selected ? R.drawable.bg_row_selected
                    : best ? R.drawable.bg_row_best : R.drawable.bg_row);
            ImageView icon = row.findViewById(R.id.rowIcon);
            icon.setImageResource(e == selected ? R.drawable.ic_check : R.drawable.ic_chevron);
            icon.setAlpha(e == selected ? 1f : 0.65f);
            row.setOnClickListener(v -> selectEndpoint(e));
            endpointList.addView(row);
        }
    }

    private String displayUrl(Endpoint e) {
        if (e.kind == Endpoint.KIND_GATEWAY) {
            String base = e.connectBase();
            if (e.path != null && !e.path.isEmpty()) {
                return base + e.path;
            }
            return base + "  ·  自动发现入口";
        }
        return e.resolvedUrl != null ? e.resolvedUrl : e.url;
    }

    private String latencyText(Endpoint e) {
        switch (e.state) {
            case Endpoint.STATE_READY:
                return getString(R.string.latency_ms, e.latency);
            case Endpoint.STATE_NEEDS_LOGIN:
                return getString(R.string.latency_login);
            case Endpoint.STATE_PROBING:
                return "…";
            case Endpoint.STATE_FAIL:
                return getString(R.string.latency_dash);
            default:
                return "";
        }
    }

    private String stateText(Endpoint e) {
        switch (e.state) {
            case Endpoint.STATE_READY:
                return e == bestReady ? getString(R.string.state_best) : getString(R.string.state_ready);
            case Endpoint.STATE_NEEDS_LOGIN:
                return getString(R.string.state_login);
            case Endpoint.STATE_PROBING:
                return getString(R.string.state_probing);
            case Endpoint.STATE_FAIL:
                return e.detail == null || e.detail.isEmpty()
                        ? getString(R.string.state_fail) : e.detail;
            default:
                return getString(R.string.state_idle);
        }
    }

    private int dotRes(int state) {
        switch (state) {
            case Endpoint.STATE_READY:
                return R.drawable.bg_dot_ready;
            case Endpoint.STATE_NEEDS_LOGIN:
                return R.drawable.bg_dot_warn;
            case Endpoint.STATE_FAIL:
                return R.drawable.bg_dot_fail;
            case Endpoint.STATE_PROBING:
                return R.drawable.bg_dot_probing;
            default:
                return R.drawable.bg_dot_idle;
        }
    }

    private int latencyPill(Endpoint e) {
        if (e.state == Endpoint.STATE_READY) {
            if (e.latency < 120) return R.drawable.bg_pill_green;
            return e.latency < 400 ? R.drawable.bg_pill_amber : R.drawable.bg_pill_red;
        }
        if (e.state == Endpoint.STATE_NEEDS_LOGIN) return R.drawable.bg_pill_amber;
        return R.drawable.bg_pill_soft;
    }

    /** Tap = pick only: highlight the row and arm the enter button; never opens directly. */
    private void selectEndpoint(Endpoint e) {
        Prefs.setSelectedLine(this, e.id);
        autoOpened = true;
        cancelAutoOpen();
        render();
        updatePrimaryForSelection();
    }

    private void openEndpoint(Endpoint e) {
        if (e.kind == Endpoint.KIND_GATEWAY && (e.path == null || e.path.isEmpty())) {
            startLogin(e);
            return;
        }
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra(WebActivity.EXTRA_URL, e.launchUrl());
        i.putExtra(WebActivity.EXTRA_NAME, e.name);
        i.putExtra(WebActivity.EXTRA_ENDPOINT_ID, e.id);
        startActivityForResult(i, REQ_WEB);
    }

    private void startLogin(Endpoint e) {
        if (e == null) {
            UiUtil.toast(this, getString(R.string.toast_no_line));
            openSettings();
            return;
        }
        String base = e.loginUrl();
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra(WebActivity.EXTRA_CAPTURE, true);
        i.putExtra(WebActivity.EXTRA_LOGIN_BASE, base);
        i.putExtra(WebActivity.EXTRA_NAME, e.name);
        startActivityForResult(i, REQ_WEB);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFlow.onResume();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            endpoints = EndpointStore.load(this);
            reconcileSelection();
            render();
            if (resultCode == RESULT_OK) {
                probeAll();
            } else if (endpoints.isEmpty()) {
                showEmptyState();
            }
        } else if (requestCode == REQ_WEB) {
            endpoints = EndpointStore.load(this);
            render();
        }
    }

    @Override
    protected void onDestroy() {
        if (bob != null) bob.cancel();
        if (twinkleA != null) twinkleA.cancel();
        if (twinkleB != null) twinkleB.cancel();
        if (updateFlow != null) updateFlow.destroy();
        ui.removeCallbacksAndMessages(null);
        if (pool != null) pool.shutdownNow();
        if (scanPool != null) scanPool.shutdownNow();
        super.onDestroy();
    }
}
