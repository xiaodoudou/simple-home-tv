package com.simplehometv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Two panes: categories on the left, the selected category's settings on the right. */
public class SettingsActivity extends Activity {

    static final String EXTRA_PAGE = "page";

    private static final String[] PAGES = {"Home", "Network", "Display & sound", "Inputs", "Apps", "Screensaver", "About", "Android settings"};
    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_wifi, R.drawable.ic_display, R.drawable.ic_input,
            R.drawable.ic_apps, R.drawable.ic_moon, R.drawable.ic_info, R.drawable.ic_settings};

    private static final class Row {
        final String title, sub, value;
        final Runnable action;   // null: information only

        Row(String title, String sub, String value, Runnable action) {
            this.title = title;
            this.sub = sub;
            this.value = value;
            this.action = action;
        }
    }

    private float dp;
    private Prefs prefs;
    private final List<View> catViews = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private LinearLayout rowBox;
    private ScrollView rowScroll;
    private TextView pageTitle;
    private int page, rowFocus;
    private boolean inRows;
    private boolean clipPage;              // the aerial clip list, opened from the Home page
    private int rowFocusAfterShow = -1;
    private static final int AERIAL_ROW = 2;   // "Aerial videos" on the Home page
    private final OkKey okKey = new OkKey();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        dp = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        buildUi();
        page = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_PAGE, 0), PAGES.length - 1));
        inRows = getIntent().hasExtra(EXTRA_PAGE);
        showPage(page);
        if (inRows) focusRow(firstActionable());
        else focusCat(page);
    }

    @Override protected void onResume() {
        super.onResume();
        // Back from a system screen: values (network, installed apps) may have changed.
        if (rowBox != null) {
            int keep = rowFocus;
            showPage(page);
            if (inRows) focusRow(Math.min(keep, rows.size() - 1));
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFF14171D, 0xFF0A0B0F}));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(px(64), px(44), px(24), px(40));
        TextView title = Ui.text(this, Ui.semibold(), 28, Ui.TEXT);
        title.setText("Settings");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.bottomMargin = px(26);
        left.addView(title, tl);
        for (int i = 0; i < PAGES.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(14), 0, px(14), 0);
            ImageView ic = new ImageView(this);
            ic.setImageResource(ICONS[i]);
            row.addView(ic, new LinearLayout.LayoutParams(px(20), px(20)));
            TextView t = Ui.text(this, Ui.medium(), 15, Ui.TEXT);
            t.setText(PAGES[i]);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.leftMargin = px(14);
            row.addView(t, l);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, px(44));
            rl.bottomMargin = px(4);
            left.addView(row, rl);
            catViews.add(row);
        }
        root.addView(left, new FrameLayout.LayoutParams(px(300), -1));

        View divider = new View(this);
        divider.setBackgroundColor(0x14FFFFFF);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(px(1), -1);
        dl.leftMargin = px(300);
        dl.topMargin = px(48);
        dl.bottomMargin = px(48);
        root.addView(divider, dl);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(40), px(52), px(64), 0);
        pageTitle = Ui.text(this, Ui.semibold(), 22, Ui.TEXT);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.leftMargin = px(16);
        pl.bottomMargin = px(22);
        right.addView(pageTitle, pl);
        rowScroll = new ScrollView(this);
        rowScroll.setVerticalScrollBarEnabled(false);
        rowScroll.setFocusable(false);
        rowScroll.setVerticalFadingEdgeEnabled(true);
        rowScroll.setFadingEdgeLength(px(36));
        rowScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rowScroll.setClipToPadding(false);
        rowBox = new LinearLayout(this);
        rowBox.setOrientation(LinearLayout.VERTICAL);
        rowScroll.addView(rowBox, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rsl = new LinearLayout.LayoutParams(-1, 0, 1);
        rsl.bottomMargin = px(44);
        right.addView(rowScroll, rsl);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-1, -1);
        rl.leftMargin = px(301);
        root.addView(right, rl);

        setContentView(root);
    }

    // ---- pages ----

    private void showPage(int p) {
        page = p;
        if (p != 0) clipPage = false;
        pageTitle.setText(clipPage ? "Aerial videos" : PAGES[p]);
        rows.clear();
        switch (p) {
            case 0: if (clipPage) clipRows(); else homeRows(); break;
            case 1: networkRows(); break;
            case 2: displayRows(); break;
            case 3: inputRows(); break;
            case 4: appRows(); break;
            case 5: screensaverRows(); break;
            case 6: aboutRows(); break;
            default: androidRows();
        }
        rowBox.removeAllViews();
        rowViews.clear();
        for (Row r : rows) {
            View v = rowView(r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = px(4);
            rowBox.addView(v, lp);
            rowViews.add(v);
        }
        rowScroll.scrollTo(0, 0);
        styleCats();
    }

    private void homeRows() {
        String[] rowsNames = {"Automatic", "One row", "Two rows"};
        rows.add(new Row("Dock layout", "Automatic uses two rows when one would make tiles too small",
                rowsNames[prefs.dockRows()], () -> prefs.setDockRows((prefs.dockRows() + 1) % 3)));
        rows.add(new Row("Aerial video background", "Apple's aerial footage, downloaded once and kept on the device",
                onOff(prefs.aerial()), () -> prefs.setAerial(!prefs.aerial())));
        int chosen = Aerials.chosen(this, prefs.aerialIds()).size();
        rows.add(new Row("Aerial videos", "Pick which ones play", chosen + " of " + Aerials.all(this).size(), () -> {
            clipPage = true;
            rowFocusAfterShow = 0;
        }));
        rows.add(new Row("Aerial order", null, prefs.aerialShuffle() ? "Shuffle" : "List order",
                () -> prefs.setAerialShuffle(!prefs.aerialShuffle())));
        boolean uhd = AerialBackground.use4k(this);
        rows.add(new Row("Aerial quality", "4K clips are about 330 MB each, Full HD about 230 MB",
                uhd ? "4K" : "Full HD", () -> prefs.setAerial4k(!uhd)));
        rows.add(new Row("Show app content on focus", "Continue watching and channels above the dock, with artwork",
                onOff(prefs.spotlight()), () -> prefs.setSpotlight(!prefs.spotlight())));
        long mb = dirSize(new File(getCacheDir(), "aerial")) / (1024 * 1024);
        rows.add(new Row("Clear downloaded videos", "They download again when needed", mb + " MB", () -> {
            File[] fs = new File(getCacheDir(), "aerial").listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }));
    }

    private void clipRows() {
        java.util.Set<String> ids = prefs.aerialIds();
        boolean uhd = AerialBackground.use4k(this);
        for (Aerials.Clip clip : Aerials.all(this)) {
            boolean on = ids.contains(clip.id);
            rows.add(new Row(clip.name, (uhd ? clip.mb4k : clip.mb) + " MB", on ? "✓" : null, () -> {
                java.util.Set<String> now = prefs.aerialIds();
                if (!now.remove(clip.id)) now.add(clip.id);
                if (!now.isEmpty()) prefs.setAerialIds(now);
            }));
        }
    }

    private void networkRows() {
        rows.add(new Row("Wi-Fi & network", null, connectionName(),
                () -> open(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)));
        rows.add(new Row("Bluetooth & remotes", null, null, () -> open(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)));
    }

    private void displayRows() {
        rows.add(new Row("Display", "Resolution, picture and screen", null, () -> open(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)));
        rows.add(new Row("Sound", "Speakers, audio output and volume", null, () -> open(Settings.ACTION_SOUND_SETTINGS, Settings.ACTION_SETTINGS)));
        rows.add(new Row("Date & time", null, null, () -> open(Settings.ACTION_DATE_SETTINGS, Settings.ACTION_SETTINGS)));
    }

    private void inputRows() {
        List<Tile> inputs = Catalog.inputs(this);
        if (inputs.isEmpty()) {
            rows.add(new Row("No TV inputs", "This device has no HDMI or AV inputs", null, null));
            return;
        }
        Pins pins = new Pins(prefs.pins());
        java.util.Set<String> hidden = prefs.hiddenTiles();
        for (Tile t : inputs) {
            boolean on = pins.isPinned(t.id), gone = !on && hidden.contains(t.id);
            rows.add(new Row(t.label, t.on ? "On" : t.connected ? "In standby" : "Nothing connected",
                    on ? "On home" : gone ? "Hidden" : "In All apps", () -> {
                if (gone) {
                    prefs.setHidden(t.id, false);
                    return;
                }
                Pins p = new Pins(prefs.pins());
                p.toggle(t.id);
                prefs.setPins(p.serialize());
            }));
        }
        rows.add(new Row("Live preview on focus", "Shows the input's picture when its tile is focused",
                onOff(prefs.preview()), () -> prefs.setPreview(!prefs.preview())));
    }

    private void appRows() {
        rows.add(new Row("Manage apps", "Storage, permissions, uninstall", null,
                () -> open(Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_SETTINGS)));
        List<String> stores = Suggestions.stores(this);
        String store = Suggestions.store(this, prefs.store());
        if (store == null) {
            rows.add(new Row("App store", "Install APKUpdater to get apps without Google Play", "None", null));
        } else if (stores.size() > 1) {
            rows.add(new Row("App store", "Used by Get below. Select to switch", Suggestions.storeName(store),
                    () -> prefs.setStore(stores.get((stores.indexOf(store) + 1) % stores.size()))));
        } else {
            rows.add(new Row("App store", "Used by Get below", Suggestions.storeName(store), () -> launch(store)));
        }
        for (Suggestions.App a : Suggestions.list(this)) {
            if (Suggestions.installed(this, a.pkg)) {
                rows.add(new Row(a.name, a.why, "Installed", () -> launch(a.pkg)));
            } else if (store != null && a.site == null) {
                // A store without market:// links (APKUpdater) can only open on its own search.
                Intent page = Suggestions.pageIntent(this, store, a.pkg);
                rows.add(new Row(a.name, page != null ? a.why : a.why + ". Search for it in " + Suggestions.storeName(store),
                        "Get", page != null ? () -> startSafe(page) : () -> launch(store)));
            } else if (a.site != null) {
                rows.add(new Row(a.name, a.why + ". Get it from " + a.site, null, null));
            } else {
                rows.add(new Row(a.name, a.why, "Needs a store", null));
            }
        }
        java.util.Set<String> hidden = prefs.hiddenTiles();
        Pins pins = new Pins(prefs.pins());
        List<Tile> gone = new ArrayList<>();
        for (Tile t : Catalog.apps(this)) if (hidden.contains(t.id) && !pins.isPinned(t.id)) gone.add(t);
        if (gone.isEmpty()) return;
        rows.add(new Row("Hidden from All apps", "Select one to show it again", null, null));
        for (Tile t : gone) rows.add(new Row(t.label, null, "Show", () -> prefs.setHidden(t.id, false)));
    }

    private void screensaverRows() {
        boolean ours = String.valueOf(Settings.Secure.getString(getContentResolver(), "screensaver_components"))
                .startsWith(getPackageName() + "/");
        rows.add(new Row("Use Simple Home's screensaver", ours ? null : "Pick \"Simple Home\" in the TV's screensaver list",
                ours ? "On" : "Off", () -> open(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)));
        int photos = Photos.list(this).size();
        rows.add(new Row("Show", photos == 0 ? "No photos on the TV yet" : null,
                prefs.screensaverPhotos() && photos > 0 ? "Your photos" : "Aerial videos",
                () -> prefs.setScreensaverPhotos(photos > 0 && !prefs.screensaverPhotos())));
        rows.add(new Row("Your photos", "Private: chosen in Google Photos on your PC with google_photos_to_tv.py, then kept on the TV",
                photos + " photos", null));
        rows.add(new Row("Clock", "Time and date in the corner", onOff(prefs.screensaverClock()),
                () -> prefs.setScreensaverClock(!prefs.screensaverClock())));
        rows.add(new Row("Start now", null, null, () -> startSafe(new Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        rows.add(new Row("When it starts", "Set by the TV", null, () -> open(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)));
    }

    private void aboutRows() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        rows.add(new Row("Simple Home", "A clean home screen for Android TV", version, null));
        rows.add(new Row("Device", Build.MANUFACTURER + " " + Build.MODEL, "Android " + Build.VERSION.RELEASE, null));
        rows.add(new Row("Device info", null, null, () -> open(Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)));
    }

    private void androidRows() {
        rows.add(new Row("Open Android settings", "The TV's own settings: picture, sound, network, accounts and more",
                null, this::openAndroidSettings));
    }

    private void openAndroidSettings() {
        open(Settings.ACTION_SETTINGS, null);
    }

    private View rowView(Row r) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), px(12), px(16), px(12));
        row.setMinimumHeight(px(54));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, Ui.medium(), 16, r.action == null ? Ui.TEXT_DIM : Ui.TEXT);
        t.setText(r.title);
        texts.addView(t);
        if (r.sub != null) {
            TextView s = Ui.text(this, Ui.regular(), 14, Ui.TEXT_FAINT);
            s.setText(r.sub);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = px(4);
            texts.addView(s, sl);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (r.value != null) {
            TextView v = Ui.text(this, Ui.medium(), 14, Ui.TEXT_DIM);
            v.setText(r.value);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setMaxWidth(px(220));
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.leftMargin = px(16);
            row.addView(v, vl);
        }
        return row;
    }

    // ---- focus ----

    private void focusCat(int i) {
        inRows = false;
        int p = Math.max(0, Math.min(i, PAGES.length - 1));
        if (p != page) showPage(p);
        styleCats();
        styleRows();
    }

    private void focusRow(int i) {
        if (rows.isEmpty()) return;
        inRows = true;
        rowFocus = Math.max(0, Math.min(i, rows.size() - 1));
        styleCats();
        styleRows();
        View v = rowViews.get(rowFocus);
        rowScroll.post(() -> {
            int y = v.getTop(), h = rowScroll.getHeight(), cur = rowScroll.getScrollY();
            if (y < cur) rowScroll.smoothScrollTo(0, y);
            else if (y + v.getHeight() > cur + h) rowScroll.smoothScrollTo(0, y + v.getHeight() - h);
        });
    }

    private int firstActionable() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).action != null) return i;
        return 0;
    }

    private void styleCats() {
        for (int i = 0; i < catViews.size(); i++) {
            boolean sel = i == page, focused = sel && !inRows;
            ViewGroup row = (ViewGroup) catViews.get(i);
            row.setBackground(focused ? Ui.rounded(0xFFFFFFFF, px(12)) : sel ? Ui.rounded(0x1FFFFFFF, px(12)) : null);
            int color = focused ? 0xFF111317 : sel ? Ui.TEXT : Ui.TEXT_DIM;
            ((ImageView) row.getChildAt(0)).setColorFilter(color);
            ((TextView) row.getChildAt(1)).setTextColor(color);
        }
    }

    private void styleRows() {
        for (int i = 0; i < rowViews.size(); i++) {
            boolean f = inRows && i == rowFocus;
            Row r = rows.get(i);
            ViewGroup row = (ViewGroup) rowViews.get(i);
            row.setBackground(f ? Ui.rounded(0xFFFFFFFF, px(12)) : null);
            ViewGroup texts = (ViewGroup) row.getChildAt(0);
            ((TextView) texts.getChildAt(0)).setTextColor(f ? 0xFF111317 : r.action == null ? Ui.TEXT_DIM : Ui.TEXT);
            if (texts.getChildCount() > 1) ((TextView) texts.getChildAt(1)).setTextColor(f ? 0x99111317 : Ui.TEXT_FAINT);
            if (row.getChildCount() > 1) ((TextView) row.getChildAt(1)).setTextColor(f ? 0xFF111317 : Ui.TEXT_DIM);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
                || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (down) okKey.onDown(e.getRepeatCount(), false, e.getEventTime());
            else if (okKey.onUp()) select();
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (!down && !e.isCanceled()) {
                if (clipPage) leaveClipPage();
                else if (inRows) focusCat(page);
                else finish();
            }
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inRows) focusRow(rowFocus - 1);
                else focusCat(page - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inRows) focusRow(rowFocus + 1);
                else focusCat(page + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!inRows) focusRow(firstActionable());
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (clipPage) leaveClipPage();
                else if (inRows) focusCat(page);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void select() {
        if (!inRows && page == PAGES.length - 1) {
            openAndroidSettings();
            return;
        }
        if (!inRows) {
            focusRow(firstActionable());
            return;
        }
        if (rowFocus >= rows.size()) return;
        Row r = rows.get(rowFocus);
        if (r.action == null) return;
        int keep = rowFocus;
        rowFocusAfterShow = -1;
        r.action.run();
        showPage(page);
        focusRow(rowFocusAfterShow >= 0 ? rowFocusAfterShow : keep);
    }

    private void leaveClipPage() {
        clipPage = false;
        showPage(0);
        focusRow(AERIAL_ROW);
    }

    // ---- helpers ----

    private void open(String action, String fallback) {
        if (startSafe(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        if (fallback != null) startSafe(new Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startSafe(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean startSafe(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String connectionName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) return "Not connected";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                String ssid = wm == null ? null : wm.getConnectionInfo().getSSID();
                return ssid == null || ssid.contains("unknown") ? "Wi-Fi" : ssid.replace("\"", "");
            }
            return "Connected";
        } catch (Exception e) {
            return null;
        }
    }

    private static String onOff(boolean on) {
        return on ? "On" : "Off";
    }

    private static long dirSize(File dir) {
        long n = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
