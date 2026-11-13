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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final long HOME_RETRY_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());

    private static HomeButton running;
    private static boolean homeInFront;
    private static long homeKeyAt = Long.MIN_VALUE / 2;

    private boolean active;                 // the system's home is not Simple Home
    private String stockHome;               // its package, unless it is only a fallback
    private Set<String> otherHomes = Collections.emptySet();   // class names of every other HOME activity

    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!"homekey".equals(i.getStringExtra("reason"))) return;
            if (homeInFront) {
                homeKeyAt = SystemClock.uptimeMillis();
            } else if (active) {
                // A restarted PatchWall sends no window event, so Home from an app is answered here too. The stock
                // home starts some 30 ms after the key: the second start puts Simple Home back if it lost that race.
                main.post(HomeButton.this::openHome);
                main.postDelayed(HomeButton.this::openHome, HOME_RETRY_MS);
            }
        }
    };

    // Read once, the stock home goes stale when PatchWall updates or is turned off and on: Home then stays on it.
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            refreshHomes();
        }
    };

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")    // the flags exist from Android 13 only
    @Override protected void onServiceConnected() {
        running = this;
        refreshHomes();
        new Prefs(this).setHomeButton(true);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            AccessibilityServiceInfo info = getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(homeKey, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(homeKey, f);
        IntentFilter p = new IntentFilter();
        p.addAction(Intent.ACTION_PACKAGE_ADDED);
        p.addAction(Intent.ACTION_PACKAGE_REMOVED);
        p.addAction(Intent.ACTION_PACKAGE_CHANGED);
        p.addAction(Intent.ACTION_PACKAGE_REPLACED);
        p.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packages, p, Context.RECEIVER_EXPORTED);
        else registerReceiver(packages, p);
        // Bound after boot or an update, the stock home may already be up with no event left to catch.
        android.view.accessibility.AccessibilityNodeInfo front = getRootInActiveWindow();
        if (front != null && active && String.valueOf(front.getPackageName()).equals(stockHome)) openHome();
    }

    /**
     * Matches the stock home by package, as its popups count too, and any other HOME activity (Xiaomi's FallbackHome
     * when PatchWall is off) by class only: its package also holds the TV's settings.
     */
    private void refreshHomes() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        PackageManager pm = getPackageManager();
        // Not the resolved home: once PatchWall is turned off and on, Android resolves Simple Home (it holds the home
        // role) while Xiaomi's firmware still starts PatchWall.
        String stock = null;
        int best = -1;
        Set<String> others = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(home, 0)) {
            if (r.activityInfo == null || r.activityInfo.packageName.equals(getPackageName())) continue;
            others.add(r.activityInfo.name);
            if (r.priority > best) {
                best = r.priority;
                stock = r.activityInfo.packageName;
            }
        }
        stockHome = best >= 0 ? stock : null;      // a negative priority is only a fallback screen
        otherHomes = others;
        active = stockHome != null || stockHome(this) != null;
        Log.i(TAG, "homes: stock " + stockHome + ", " + others.size() + " others, active " + active);
    }

    private boolean isOtherHome(AccessibilityEvent e) {
        if (!active) return false;
        return String.valueOf(e.getPackageName()).equals(stockHome) || otherHomes.contains(String.valueOf(e.getClassName()));
    }

    /** Switched off by the user in the TV's settings (a force-stop ends the process without this). */
    @Override public boolean onUnbind(Intent intent) {
        new Prefs(this).setHomeButton(false);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        main.removeCallbacksAndMessages(null);
        for (BroadcastReceiver r : new BroadcastReceiver[]{homeKey, packages}) {
            try {
                unregisterReceiver(r);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || !active) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) openHome();
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isOtherHome(e)) openHome();
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
                && new Prefs(c).homeButton();
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
