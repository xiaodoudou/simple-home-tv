package com.simplehometv;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * For TVs whose firmware only lets a system app be the home (Xiaomi's China firmware): brings Simple Home back when
 * the stock home comes up (Home in another app, Back out of one, boot). Idle where Simple Home already is the home.
 * Home pressed in Simple Home itself is answered as it pauses (see {@link #homePaused}), so no keys are filtered from
 * Android 12: on Xiaomi a filter stops a held volume key from repeating. Up to Android 11 Home holds app starts back
 * for five seconds, so there Home is still taken before the system sees it.
 * The approach follows aerial-tv's Home Guard (github.com/pengchujin/aerial-tv, MIT).
 */
public class HomeButton extends AccessibilityService {

    private static final String TAG = "HomeButton";
    private static final long HOME_PAUSE_MS = 400;     // Home pauses the home 30-80 ms after the key (MiTV-MFTR0)

    private static HomeButton running;
    private static boolean homeInFront;
    private static long homeKeyAt = Long.MIN_VALUE / 2;

    private String stockHome;

    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (homeInFront && "homekey".equals(i.getStringExtra("reason"))) homeKeyAt = SystemClock.uptimeMillis();
        }
    };

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")    // the flags exist from Android 13 only
    @Override protected void onServiceConnected() {
        running = this;
        stockHome = stockHome(this);
        new Prefs(this).setHomeButton(true);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            AccessibilityServiceInfo info = getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(homeKey, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(homeKey, f);
        // Bound after boot or an update, the stock home may already be up with no event left to catch.
        android.view.accessibility.AccessibilityNodeInfo front = getRootInActiveWindow();
        if (stockHome != null && front != null && stockHome.equals(String.valueOf(front.getPackageName()))) openHome();
    }

    /** Switched off by the user in the TV's settings (a force-stop ends the process without this). */
    @Override public boolean onUnbind(Intent intent) {
        new Prefs(this).setHomeButton(false);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        try {
            unregisterReceiver(homeKey);
        } catch (Exception ignored) {
        }
        super.onDestroy();
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

    // ---- told by the home ----

    static void homeResumed() {
        homeInFront = true;
    }

    /**
     * The home is pausing. Right after a Home press that is the stock home starting over it: starting Simple Home again
     * now, before the stock home's task is put on top, keeps it off the screen.
     */
    static void homePaused() {
        homeInFront = false;
        HomeButton s = running;
        if (s != null && s.stockHome != null && SystemClock.uptimeMillis() - homeKeyAt < HOME_PAUSE_MS) {
            homeKeyAt = Long.MIN_VALUE / 2;
            s.openHome();
        }
    }

    // ---- staying on ----

    /**
     * Turns the service back on after the firmware dropped it: Android removes a force-stopped app's accessibility
     * services for good, and Xiaomi force-stops apps at standby and after an ANR. Needs WRITE_SECURE_SETTINGS,
     * granted over adb, and only acts when the user had it on.
     */
    static void ensureOn(Context c) {
        if (!wanted(c)) return;
        String me = me(c);
        List<String> all = enabled(c);
        try {
            if (!all.contains(me)) {
                all.add(me);
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", all));
                Log.i(TAG, "was switched off; turned it back on");
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Exception e) {
            Log.w(TAG, "could not turn it back on: " + e);
        }
    }

    /** Enabled but never bound (seen after packages changed on MiTV-MFTR0): off and on again makes Android bind it. */
    static void rebindIfStalled(Context c) {
        if (running != null || !wanted(c)) return;
        String me = me(c);
        List<String> all = enabled(c);
        if (!all.remove(me)) return;
        try {
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", all));
            // Two writes in a row are merged and Android sees no change.
            new Handler(Looper.getMainLooper()).postDelayed(() -> ensureOn(c), 1500);
            Log.i(TAG, "enabled but not running; asked Android to bind it again");
        } catch (Exception e) {
            Log.w(TAG, "could not rebind: " + e);
        }
    }

    private static boolean wanted(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                && new Prefs(c).homeButton() && stockHome(c) != null;
    }

    private static String me(Context c) {
        return new ComponentName(c, HomeButton.class).flattenToShortString();
    }

    private static List<String> enabled(Context c) {
        List<String> out = new ArrayList<>();
        String all = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (all != null) for (String s : all.split(":")) {
            ComponentName n = ComponentName.unflattenFromString(s);
            if (n != null) out.add(n.flattenToShortString());
        }
        return out;
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
