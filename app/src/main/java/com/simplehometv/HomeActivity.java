package com.simplehometv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen: a cinematic backdrop, a clock, and one dock of tiles (pinned apps + inputs + All apps).
 * Selecting All apps slides the dock up to a pinned bar and reveals the other apps and inputs below.
 */
public class HomeActivity extends Activity {

    private enum Mode { HOME, DRAWER }

    private float dp;
    private SharedPreferences prefs;
    private Pins pins;

    private FrameLayout root;
    private View backdrop;
    private TextView clock, date;
    private LinearLayout dock;              // home dock row (pinned + all apps)
    private HorizontalScrollView dockScroll;
    private ScrollView drawerScroll;
    private LinearLayout drawerBox;         // pinned bar + unpinned grid

    private final List<Tile> catalog = new ArrayList<>();   // apps + inputs
    private final List<Tile> dockTiles = new ArrayList<>(); // what the home dock shows
    private final List<View> dockViews = new ArrayList<>();
    private final List<Tile> drawerTiles = new ArrayList<>();
    private final List<View> drawerViews = new ArrayList<>();

    private Mode mode = Mode.HOME;
    private int focus = 0;
    private int drawerCols = 6;
    private int drawerBarCount;             // pinned tiles at the front of the drawer (the bar)

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::updateClock;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pins = new Pins(prefs.getString("pins", null));
        buildUi();
        load();
        renderHome();
        focusDock(0);
        updateClock();
    }

    @Override protected void onResume() {
        super.onResume();
        load();
        if (mode == Mode.HOME) renderHome();
        else renderDrawer();
        updateClock();
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    // ---- data ----

    private void load() {
        catalog.clear();
        catalog.addAll(Catalog.apps(this));
        catalog.addAll(Catalog.inputs(this));
        // First run: pin a few apps so the dock is not empty.
        if (pins.count() == 0) {
            int pinned = 0;
            for (Tile t : catalog) {
                if (t.kind == Tile.Kind.APP && pinned < 6) {
                    pins.pin(t.id);
                    pinned++;
                }
            }
            savePins();
        }
    }

    private void savePins() {
        prefs.edit().putString("pins", pins.serialize()).apply();
    }

    private List<Tile> pinnedTiles() {
        List<Tile> out = new ArrayList<>();
        for (Tile t : pins.order(catalog, t -> t.id)) if (pins.isPinned(t.id)) out.add(t);
        return out;
    }

    private List<Tile> unpinnedTiles() {
        List<Tile> out = new ArrayList<>();
        for (Tile t : catalog) if (!pins.isPinned(t.id)) out.add(t);
        return out;
    }

    // ---- ui scaffold ----

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070B);

        backdrop = new View(this);
        applyBackdrop(0xFF14343C);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        clock = new TextView(this);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
        clock.setLetterSpacing(-0.02f);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2);
        cl.gravity = Gravity.TOP | Gravity.START;
        cl.leftMargin = px(48);
        cl.topMargin = px(40);
        root.addView(clock, cl);

        date = new TextView(this);
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        date.setLetterSpacing(0.08f);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2);
        dl.gravity = Gravity.TOP | Gravity.START;
        dl.leftMargin = px(50);
        dl.topMargin = px(104);
        root.addView(date, dl);

        ImageView gear = new ImageView(this);
        gear.setImageResource(android.R.drawable.ic_menu_manage);
        gear.setColorFilter(0xFFFFFFFF);
        gear.setBackground(circle(0x26FFFFFF));
        gear.setPadding(px(8), px(8), px(8), px(8));
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(px(38), px(38));
        gl.gravity = Gravity.TOP | Gravity.END;
        gl.rightMargin = px(48);
        gl.topMargin = px(44);
        gear.setOnClickListener(v -> openSettings());
        root.addView(gear, gl);

        dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setClipToPadding(false);
        dockScroll.setPadding(px(48), 0, px(48), 0);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dockScroll.addView(dock, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams dsl = new FrameLayout.LayoutParams(-1, px(96));
        dsl.gravity = Gravity.BOTTOM;
        dsl.bottomMargin = px(44);
        root.addView(dockScroll, dsl);

        drawerScroll = new ScrollView(this);
        drawerScroll.setVisibility(View.GONE);
        drawerScroll.setBackgroundColor(0xF2090A0E);
        drawerBox = new LinearLayout(this);
        drawerBox.setOrientation(LinearLayout.VERTICAL);
        drawerBox.setPadding(px(48), px(44), px(48), px(56));
        drawerScroll.addView(drawerBox, new FrameLayout.LayoutParams(-1, -2));
        root.addView(drawerScroll, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    // ---- home dock ----

    private void renderHome() {
        mode = Mode.HOME;
        drawerScroll.setVisibility(View.GONE);
        dockScroll.setVisibility(View.VISIBLE);
        dock.removeAllViews();
        dockViews.clear();
        dockTiles.clear();
        dockTiles.addAll(pinnedTiles());
        dockTiles.add(Tile.allApps());
        for (int i = 0; i < dockTiles.size(); i++) {
            View v = tileView(dockTiles.get(i), 132, 74);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(132), px(74));
            if (i > 0) lp.leftMargin = px(14);
            dock.addView(v, lp);
            dockViews.add(v);
        }
    }

    private void focusDock(int i) {
        if (dockViews.isEmpty()) return;
        focus = Math.max(0, Math.min(i, dockViews.size() - 1));
        for (int k = 0; k < dockViews.size(); k++) setFocused(dockViews.get(k), k == focus);
        View v = dockViews.get(focus);
        dockScroll.post(() -> dockScroll.smoothScrollTo((int) (v.getLeft() - px(120)), 0));
        glowFor(dockTiles.get(focus));
    }

    // ---- all apps drawer ----

    private void openDrawer() {
        mode = Mode.DRAWER;
        dockScroll.setVisibility(View.GONE);
        drawerScroll.setVisibility(View.VISIBLE);
        drawerScroll.scrollTo(0, 0);
        renderDrawer();
        drawerScroll.post(() -> focusDrawer(0));
    }

    private void renderDrawer() {
        drawerBox.removeAllViews();
        drawerViews.clear();
        drawerTiles.clear();
        List<Tile> pinned = pinnedTiles();
        List<Tile> rest = unpinnedTiles();
        drawerBarCount = pinned.size();

        drawerBox.addView(sectionLabel("PINNED", false));
        LinearLayout bar = row();
        for (int i = 0; i < pinned.size(); i++) {
            View v = tileView(pinned.get(i), 124, 70);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(124), px(70));
            if (i > 0) lp.leftMargin = px(14);
            bar.addView(v, lp);
            drawerViews.add(v);
            drawerTiles.add(pinned.get(i));
        }
        drawerBox.addView(bar);

        drawerBox.addView(sectionLabel("OTHER APPS & INPUTS", true));
        LinearLayout gridRow = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % drawerCols == 0) {
                gridRow = row();
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) rl.topMargin = px(14);
                gridRow.setLayoutParams(rl);
                drawerBox.addView(gridRow);
            }
            View v = tileView(rest.get(i), 124, 70);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(124), px(70));
            if (i % drawerCols > 0) lp.leftMargin = px(14);
            gridRow.addView(v, lp);
            drawerViews.add(v);
            drawerTiles.add(rest.get(i));
        }
    }

    private void focusDrawer(int i) {
        if (drawerViews.isEmpty()) {
            renderHome();
            focusDock(0);
            return;
        }
        focus = Math.max(0, Math.min(i, drawerViews.size() - 1));
        for (int k = 0; k < drawerViews.size(); k++) setFocused(drawerViews.get(k), k == focus);
        View v = drawerViews.get(focus);
        drawerScroll.post(() -> drawerScroll.smoothScrollTo(0, Math.max(0, v.getTop() - px(140))));
        glowFor(drawerTiles.get(focus));
    }

    // ---- key handling ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean back = k == KeyEvent.KEYCODE_BACK;

        if (ok && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 1) {
            onHold();
            return true;
        }
        if (!down) {
            if (up && ok) {
                onSelect();
                return true;
            }
            if (up && back) {
                onBack();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: nav(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: nav(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: nav(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: nav(0, 1); return true;
            default: return super.dispatchKeyEvent(e);
        }
    }

    private void nav(int dx, int dy) {
        if (mode == Mode.HOME) {
            if (dx != 0) focusDock(focus + dx);
            return;
        }
        // drawer: bar (one row) + grid (drawerCols per row)
        if (focus < drawerBarCount) {
            if (dx != 0) focusDrawer(clampRow(focus + dx, 0, drawerBarCount - 1));
            else if (dy > 0 && drawerViews.size() > drawerBarCount)
                focusDrawer(drawerBarCount + Math.min(focus, Math.min(drawerCols - 1, drawerViews.size() - drawerBarCount - 1)));
        } else {
            int g = focus - drawerBarCount;
            int col = g % drawerCols;
            if (dx < 0 && col > 0) focusDrawer(focus - 1);
            else if (dx > 0 && col < drawerCols - 1 && focus + 1 < drawerViews.size()) focusDrawer(focus + 1);
            else if (dy < 0 && g < drawerCols) { if (drawerBarCount > 0) focusDrawer(Math.min(col, drawerBarCount - 1)); }
            else if (dy < 0) focusDrawer(focus - drawerCols);
            else if (dy > 0 && focus + drawerCols < drawerViews.size()) focusDrawer(Math.min(focus + drawerCols, drawerViews.size() - 1));
        }
    }

    private int clampRow(int i, int lo, int hi) {
        return Math.max(lo, Math.min(i, hi));
    }

    private void onSelect() {
        Tile t = mode == Mode.HOME ? dockTiles.get(focus) : drawerTiles.get(focus);
        if (t.kind == Tile.Kind.ALL_APPS) {
            openDrawer();
            return;
        }
        if (t.launch != null) {
            try {
                startActivity(t.launch);
            } catch (Exception ignored) {
            }
        }
    }

    private void onHold() {
        Tile t = mode == Mode.HOME ? dockTiles.get(focus) : drawerTiles.get(focus);
        if (t.kind == Tile.Kind.ALL_APPS) return;
        pins.toggle(t.id);
        savePins();
        if (mode == Mode.HOME) {
            renderHome();
            focusDock(Math.min(focus, dockViews.size() - 1));
        } else {
            int keep = focus;
            renderDrawer();
            focusDrawer(Math.min(keep, drawerViews.size() - 1));
        }
    }

    private void onBack() {
        if (mode == Mode.DRAWER) {
            renderHome();
            focusDock(0);
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---- tile views ----

    private View tileView(Tile t, int wDp, int hDp) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(0xFF1C1F24, px(11)));
        clipRound(card, px(11));
        if (t.kind == Tile.Kind.APP) {
            Drawable banner = appBanner(t.pkg);
            if (banner != null) {
                ImageView img = new ImageView(this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageDrawable(banner);
                card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            } else {
                addGlyphLabel(card, appIcon(t.pkg), t.label, hDp);
            }
        } else if (t.kind == Tile.Kind.INPUT) {
            addGlyphLabel(card, tvGlyph(), t.label, hDp);
            if (t.connected) card.addView(dot(0xFF30D158), dotLp());
        } else {
            addGlyphLabel(card, appsGlyph(), t.label, hDp);
        }
        return card;
    }

    private void addGlyphLabel(FrameLayout card, Drawable glyph, String label, int hDp) {
        if (glyph != null) {
            ImageView g = new ImageView(this);
            g.setImageDrawable(glyph);
            g.setColorFilter(0xFFCDD2D6);
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(px(22), px(22));
            gp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            gp.topMargin = px((int) (hDp * 0.22f));
            card.addView(g, gp);
        }
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFE7EAEC);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        lp.bottomMargin = px((int) (hDp * 0.18f));
        card.addView(tv, lp);
    }

    private void setFocused(View v, boolean focused) {
        v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setDuration(140).start();
        v.setElevation(focused ? px(10) : 0);
        v.setForeground(focused ? ringStroke(px(11)) : null);
    }

    // ---- backdrop ----

    private void glowFor(Tile t) {
        int color = 0xFF1E3A42;
        if (t.kind == Tile.Kind.APP) color = brandColor(t.pkg);
        applyBackdrop(color);
    }

    private void applyBackdrop(int color) {
        int dark = blend(color, 0xFF05070B, 0.72f);
        int mid = blend(color, 0xFF05070B, 0.86f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{dark, mid, 0xFF05070B});
        backdrop.setBackground(g);
    }

    private int brandColor(String pkg) {
        Drawable d = appBanner(pkg);
        if (d == null) d = appIcon(pkg);
        int avg = 0xFF2A3942;
        try {
            Bitmap bmp = Bitmap.createBitmap(10, 6, Bitmap.Config.ARGB_8888);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, 10, 6);
            copy.draw(new android.graphics.Canvas(bmp));
            long r = 0, gc = 0, b = 0, w = 0;
            for (int y = 0; y < 6; y++) for (int x = 0; x < 10; x++) {
                int c = bmp.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                float[] hsv = new float[3];
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.2f) continue;
                int ww = 1 + (int) (hsv[1] * hsv[2] * 6);
                r += (long) Color.red(c) * ww;
                gc += (long) Color.green(c) * ww;
                b += (long) Color.blue(c) * ww;
                w += ww;
            }
            bmp.recycle();
            if (w > 0) avg = Color.rgb((int) (r / w), (int) (gc / w), (int) (b / w));
        } catch (Exception ignored) {
        }
        return avg;
    }

    // ---- clock ----

    private void updateClock() {
        Date now = new Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        main.removeCallbacks(tick);
        main.postDelayed(tick, 60_000 - (System.currentTimeMillis() % 60_000));
    }

    // ---- helpers ----

    private Drawable appBanner(String pkg) {
        try {
            Drawable b = getPackageManager().getApplicationBanner(pkg);
            if (b != null) return b;
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.banner != 0 ? getPackageManager().getDrawable(pkg, ai.banner, ai) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Drawable appIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private Drawable tvGlyph() {
        return getDrawable(android.R.drawable.ic_menu_view);
    }

    private Drawable appsGlyph() {
        return getDrawable(android.R.drawable.ic_dialog_dialer);
    }

    private TextView sectionLabel(String text, boolean spacedAbove) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(spacedAbove ? 32 : 2);
        lp.bottomMargin = px(12);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private View dot(int color) {
        View v = new View(this);
        v.setBackground(rounded(color, px(4)));
        return v;
    }

    private FrameLayout.LayoutParams dotLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(8), px(8));
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = px(8);
        lp.rightMargin = px(8);
        return lp;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable ringStroke(float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setStroke(px(3), 0xFFFFFFFF);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void clipRound(View v, float radius) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private int px(int dpVal) {
        return Math.round(dpVal * dp);
    }
}
