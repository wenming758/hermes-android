package xyz.a150811.hermes;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;

public final class UiUtil {
    private UiUtil() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static void applyBars(Activity a) {
        a.getWindow().setStatusBarColor(Color.parseColor("#FFF6E8"));
        a.getWindow().setNavigationBarColor(Color.parseColor("#FFF6E8"));
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        a.getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /** Hides only the bottom navigation bar; a swipe from the edge shows it briefly. */
    public static void applyImmersiveNav(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.navigationBars());
            }
            a.getWindow().setNavigationBarContrastEnforced(false);
        } else {
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
    }

    public static void clearImmersiveNav(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                c.show(WindowInsets.Type.navigationBars());
            }
        }
        applyBars(a);
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
