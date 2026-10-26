package com.simplehometv;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * For TVs whose firmware only lets a system app be the home (Xiaomi's China firmware): catches the Home button and
 * opens Simple Home, and steps in when the stock home comes up anyway (boot, Back out of an app). Idle where Simple
 * Home already is the home.
 */
public class HomeButton extends AccessibilityService {

    private String stockHome;

    @Override protected void onServiceConnected() {
        stockHome = stockHome(this);
        // Bound after boot or an update, the stock home may already be up with no event left to catch.
        android.view.accessibility.AccessibilityNodeInfo front = getRootInActiveWindow();
        if (stockHome != null && front != null && stockHome.equals(String.valueOf(front.getPackageName()))) openHome();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || stockHome == null) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) openHome();
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (stockHome == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (stockHome.equals(String.valueOf(e.getPackageName()))) openHome();
    }

    @Override public void onInterrupt() { }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** The package the system opens on Home when that is not Simple Home, else null. */
    static String stockHome(Context c) {
        ResolveInfo r = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (r == null || r.activityInfo == null) return null;
        String pkg = r.activityInfo.packageName;
        return pkg.equals(c.getPackageName()) || pkg.equals("android") ? null : pkg;
    }

    static boolean on(Context c) {
        String all = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return all != null && all.contains(new ComponentName(c, HomeButton.class).flattenToShortString());
    }
}
