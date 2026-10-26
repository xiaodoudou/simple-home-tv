package com.simplehometv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: aerial video, clock, and a dock of pinned apps and inputs. Focusing an app shows what it
 * publishes (Continue watching, channels) above the dock; focusing an input shows its live picture. All apps
 * lifts the dock to the top as a bar and lists everything else below it.
 */
public class HomeActivity extends Activity {

    private static final String TAG = "Home";

    private enum Mode { HOME, DRAWER, MENU, MOVE }
    private enum Zone { DOCK, CONTENT, GEAR }

    // Layout, in dp. The gutters keep everything clear of TV overscan (up to 5% per edge).
    private static final int GUTTER = 64, TOP = 40, BOTTOM = 44;
    private static final int RING = 4;          // room around a tile for its focus ring
    private static final int VPAD = 10;         // room above and below a row for a focused tile's zoom
    private static final int HEADER = 48;       // drawer title height above the lifted bar
    private static final float ZOOM = 1f;    // focus is the ring alone, tiles keep their size
    private static final long LIFT_MS = 560;    // the dock's trip between the home and the All apps bar
    private static final int CAPTION = 0x80F5F5F7;

    private float dp;
    private int screenWdp, screenHdp, availW;
    private Prefs prefs;
    private Pins pins;
    private Pins gridOrder;                 // All apps' order, kept like the pins as an ordered id list

    private FrameLayout root;
    private AerialBackground aerial;
    private View backShade, scrim;
    private TextView clock, date;
    private ImageView gear;
    private LinearLayout hero;
    private ImageView heroIcon;
    private TextView heroTitle, heroSub;
    private HorizontalScrollView contentScroll, dockScroll;
    private LinearLayout contentRow, dockBox;
    private FrameLayout previewFrame;
    private TvView tvView;
    private ImageView previewCover;
    private FrameLayout previewMask;    // the box's filling: blurred frame plus shade, faded to show the input
    private View previewShade;   // the paused aerial's piece behind the box, fading out as the input appears
    private View drawerBg;
    private LinearLayout drawerHeader;
    private ScrollView drawerScroll;
    private LinearLayout drawerGrid;
    private View menuScrim;
    private LinearLayout menuBox;

    private final List<Tile> catalog = new ArrayList<>();
    private final List<Tile> dockTiles = new ArrayList<>();
    private final List<View> dockViews = new ArrayList<>();
    private final List<Tile> gridTiles = new ArrayList<>();
    private final List<View> gridViews = new ArrayList<>();
    private final List<View> gridRows = new ArrayList<>();
    private final List<Content.Item> contentItems = new ArrayList<>();
    private final List<View> contentViews = new ArrayList<>();
    private DockLayout layout;
    private int gridCols, gridW, gridH, contentW, contentH;

    private Mode mode = Mode.HOME;
    private boolean resumed;
    private Zone zone = Zone.DOCK;
    private int dockFocus, contentFocus, drawerFocus;
    private int drawerGen;                      // bumps on every open and close, so a late open step does nothing
    private String spotFor;                     // tile id focused for content
    private String shownFor;                    // tile id whose content row is drawn
    private int spotGen;                        // bumps on every row change, so stale fades do nothing
    private String previewFor;                  // input id the preview is tuned to

    private final OkKey okKey = new OkKey();
    private long menuOpenedAt;
    private Mode menuReturn = Mode.HOME;
    private final List<MenuItem> menuItems = new ArrayList<>();
    private final List<View> menuViews = new ArrayList<>();
    private int menuFocus;
    private String movingId;                    // tile picked up with Move
    private Map<String, float[]> glideFrom;     // where dock tiles were drawn before a re-render
    private final List<View> moveArrows = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Map<String, Drawable> banners = new HashMap<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Map<String, Integer> brands = new HashMap<>();
    private final Map<String, Spot> spots = new HashMap<>();
    private final Map<String, List<Content.Source>> sourcesByPkg = new HashMap<>();
    private int spotToken;

    private final Runnable tick = this::updateClock;
    private final Runnable holdTimer = () -> {
        if (okKey.onTimer()) onHold();
    };
    private Runnable pendingSpot, pendingPreview;

    private static final class Spot {
        final List<Content.Source> sources;
        final List<Content.Item> items;
        final long at;

        Spot(List<Content.Source> sources, List<Content.Item> items) {
            this.sources = sources;
            this.items = items;
            this.at = SystemClock.uptimeMillis();
        }
    }

    private static final class MenuItem {
        final String label;
        final String value;         // shown on the right, or null
        final boolean checked;
        final boolean stay;         // keep the menu open after running
        final Runnable action;

        MenuItem(String label, String value, boolean checked, boolean stay, Runnable action) {
            this.label = label;
            this.value = value;
            this.checked = checked;
            this.stay = stay;
            this.action = action;
        }
    }

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        dp = getResources().getDisplayMetrics().density;
        screenWdp = Math.round(getResources().getDisplayMetrics().widthPixels / dp);
        screenHdp = Math.round(getResources().getDisplayMetrics().heightPixels / dp);
        availW = screenWdp - 2 * GUTTER;
        prefs = new Prefs(this);
        pins = new Pins(prefs.pins());
        gridOrder = new Pins(prefs.gridOrder());
        buildUi();
        load();
        renderDock();
        focusDock(0);
        updateClock();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        String keep = currentTileId();
        boolean onGear = mode == Mode.HOME && zone == Zone.GEAR;
        pins = new Pins(prefs.pins());
        gridOrder = new Pins(prefs.gridOrder());
        load();
        prefetchArt();
        if (mode == Mode.MENU) closeMenu();
        if (mode == Mode.MOVE && moveInGrid) mode = Mode.DRAWER;
        if (mode == Mode.DRAWER) resetDrawer();
        mode = Mode.HOME;
        movingId = null;
        glideFrom = null;
        moveArrows.clear();
        renderDock();
        preloadSpots();
        Tile warm = warmCandidate();
        if (warm != null && !warm.inputId.equals(warmFor)) {
            aerial.setHeld(true);           // the input's session first, then the aerial (see "input preview")
            warm(warm);
        }
        focusDock(Math.max(0, indexOf(dockTiles, keep)));
        if (onGear) focusGear();
        updateClock();
        aerial.setActive(prefs.aerial());
        aerial.resume();
    }

    /** Back from the screensaver, which took over the video player. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) aerial.resume();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(holdTimer);
        main.removeCallbacks(afterLanding);
        okKey.reset();
        main.removeCallbacks(pendingPreview);
        aerial.thaw();
        aerial.setHeld(false);
        cool();
    }

    @Override protected void onStop() {
        super.onStop();
        aerial.stop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** Home pressed while already home: back to the dock's first tile. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (mode == Mode.MOVE) endMove();
        if (mode == Mode.MENU) closeMenu();
        if (mode == Mode.DRAWER) closeDrawer();
        else focusDock(0);
    }

    // ---- data ----

    private void load() {
        catalog.clear();
        catalog.addAll(Catalog.apps(this));
        catalog.addAll(Catalog.inputs(this));
        if (pins.count() == 0 && prefs.pins() == null) {
            for (Tile t : catalog) if (t.kind == Tile.Kind.INPUT) pins.pin(t.id);
            int apps = 0;
            for (Tile t : catalog) if (t.kind == Tile.Kind.APP && apps++ < 6) pins.pin(t.id);
            savePins();
        }
        Set<String> hidden = prefs.hiddenTiles();
        catalog.removeIf(t -> hidden.contains(t.id) && !pins.isPinned(t.id));
    }

    private void savePins() {
        prefs.setPins(pins.serialize());
    }

    private boolean present(String id) {
        return indexOf(catalog, id) >= 0;
    }

    private List<Tile> pinnedTiles() {
        List<Tile> out = new ArrayList<>();
        for (Tile t : pins.order(catalog, t -> t.id)) if (pins.isPinned(t.id)) out.add(t);
        return out;
    }

    private static int indexOf(List<Tile> list, String id) {
        if (id == null) return -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }

    private String currentTileId() {
        if (mode == Mode.DRAWER && drawerFocus >= dockTiles.size()) {
            int g = drawerFocus - dockTiles.size();
            return g < gridTiles.size() ? gridTiles.get(g).id : null;
        }
        return dockFocus < dockTiles.size() ? dockTiles.get(dockFocus).id : null;
    }

    // ---- scaffold ----

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070B);
        root.setClipChildren(false);

        aerial = new AerialBackground(this);
        root.addView(aerial, full());
        previewFrame = new FrameLayout(this);
        previewFrame.setVisibility(View.GONE);
        Ui.clip(previewFrame, px(16));

        backShade = new View(this);
        backShade.setBackground(new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF2050608, 0x99050608, 0x00050608}),
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xFF050608, 0x80050608, 0x00050608, 0x00050608})}));
        backShade.setAlpha(0f);
        root.addView(backShade, full());


        scrim = new View(this);
        scrim.setBackground(scrimDrawable());
        root.addView(scrim, full());

        clock = Ui.text(this, Ui.light(), 48, Ui.TEXT);
        clock.setLetterSpacing(-0.01f);
        clock.setShadowLayer(px(10), 0, px(1), 0x59000000);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cl.leftMargin = px(GUTTER - 2);
        cl.topMargin = px(TOP);
        root.addView(clock, cl);

        date = Ui.text(this, Ui.regular(), 16, 0xB3F5F5F7);
        date.setShadowLayer(px(8), 0, px(1), 0x59000000);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        dl.leftMargin = px(GUTTER);
        dl.topMargin = px(TOP + 58);
        root.addView(date, dl);

        gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setPadding(px(10), px(10), px(10), px(10));
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(px(44), px(44), Gravity.TOP | Gravity.END);
        gl.rightMargin = px(GUTTER);
        gl.topMargin = px(TOP + 4);
        root.addView(gear, gl);
        styleGear(false);

        // Opaque cover over the picture until the input sends video; it fades, the video surface cannot.
        // Filled with the same blurred, dimmed frame as the background, so the box is invisible until the
        // input's picture fades in, and melts back into the background when it closes.
        previewMask = new FrameLayout(this);
        previewCover = new ImageView(this);
        previewCover.setScaleType(ImageView.ScaleType.MATRIX);
        previewCover.setColorFilter(Color.argb(Math.round(AerialBackground.DIM * 255), 0, 0, 0),
                android.graphics.PorterDuff.Mode.SRC_ATOP);
        previewMask.addView(previewCover, new FrameLayout.LayoutParams(-1, -1));
        // The screen's shade is drawn under the box, so the box carries its own piece of it to match.
        previewShade = new View(this);
        previewShade.setBackground(scrimDrawable());
        previewMask.addView(previewShade);
        previewFrame.addView(previewMask, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewFrame, new FrameLayout.LayoutParams(px(480), px(270)));


        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setAlpha(0f);
        heroIcon = new ImageView(this);
        heroTitle = Ui.text(this, Ui.semibold(), 30, Ui.TEXT);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setShadowLayer(px(12), 0, px(1), 0x66000000);
        heroSub = Ui.text(this, Ui.regular(), 15, 0xCCF5F5F7);
        heroSub.setSingleLine(true);
        hero.addView(heroIcon, new LinearLayout.LayoutParams(px(26), px(26)));
        LinearLayout.LayoutParams htl = new LinearLayout.LayoutParams(px(screenWdp * 6 / 10), -2);
        htl.topMargin = px(8);
        hero.addView(heroTitle, htl);
        LinearLayout.LayoutParams hsl = new LinearLayout.LayoutParams(-2, -2);
        hsl.topMargin = px(6);
        hero.addView(heroSub, hsl);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        hl.leftMargin = px(GUTTER);
        root.addView(hero, hl);

        contentScroll = rowScroll();
        contentRow = new LinearLayout(this);
        contentRow.setClipChildren(false);
        contentScroll.addView(contentRow, new FrameLayout.LayoutParams(-2, -2));
        contentScroll.setVisibility(View.GONE);
        root.addView(contentScroll, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        drawerBg = new View(this);
        drawerBg.setBackgroundColor(0xF20A0C10);
        drawerBg.setVisibility(View.GONE);
        root.addView(drawerBg, full());

        drawerHeader = new LinearLayout(this);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
        drawerHeader.setVisibility(View.GONE);
        TextView dt = Ui.text(this, Ui.semibold(), 28, Ui.TEXT);
        dt.setText(R.string.all_apps);
        drawerHeader.addView(dt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = Ui.text(this, Ui.regular(), 14, Ui.TEXT_FAINT);
        hint.setText("Hold OK to move, hide or add to your home");
        drawerHeader.addView(hint);
        FrameLayout.LayoutParams hdl = new FrameLayout.LayoutParams(-1, px(HEADER - 12), Gravity.TOP);
        hdl.leftMargin = px(GUTTER);
        hdl.rightMargin = px(GUTTER);
        hdl.topMargin = px(TOP - 4);
        root.addView(drawerHeader, hdl);

        drawerScroll = new ScrollView(this);
        drawerScroll.setVerticalScrollBarEnabled(false);
        drawerScroll.setFocusable(false);
        drawerScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        drawerScroll.setClipToPadding(false);
        drawerScroll.setVisibility(View.GONE);
        drawerScroll.setVerticalFadingEdgeEnabled(true);
        drawerScroll.setFadingEdgeLength(px(28));
        drawerGrid = new LinearLayout(this);
        drawerGrid.setOrientation(LinearLayout.VERTICAL);
        drawerGrid.setClipChildren(false);
        drawerGrid.setClipToPadding(false);
        drawerGrid.setPadding(px(GUTTER - RING), px(VPAD), px(GUTTER - RING), px(48));
        drawerScroll.addView(drawerGrid, new FrameLayout.LayoutParams(-1, -2));
        root.addView(drawerScroll, full());

        // Added after the drawer so the dock stays on top of it when lifted into the bar.
        dockScroll = rowScroll();
        dockBox = new LinearLayout(this);
        dockBox.setOrientation(LinearLayout.VERTICAL);
        dockBox.setClipChildren(false);
        dockScroll.addView(dockBox, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams dsl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        dsl.bottomMargin = px(BOTTOM - RING - VPAD);
        root.addView(dockScroll, dsl);

        menuScrim = new View(this);
        menuScrim.setBackgroundColor(0xB3000000);
        menuScrim.setVisibility(View.GONE);
        root.addView(menuScrim, full());
        menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setVisibility(View.GONE);
        menuBox.setBackground(Ui.rounded(0xFF1B1D23, px(20)));
        menuBox.setPadding(px(10), px(10), px(10), px(10));
        menuBox.setElevation(px(24));
        root.addView(menuBox, new FrameLayout.LayoutParams(px(340), -2, Gravity.CENTER));

        setContentView(root);
    }


    private static GradientDrawable scrimDrawable() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x1A000000, 0x00000000, 0x4D000000, 0xD9000000});
    }

    private HorizontalScrollView rowScroll() {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setClipChildren(false);
        s.setFocusable(false);
        s.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        s.setPadding(px(GUTTER - RING), px(VPAD), px(GUTTER - RING), px(VPAD));
        return s;
    }

    // ---- dock ----

    private void renderDock() {
        dockBox.removeAllViews();
        dockViews.clear();
        dockTiles.clear();
        dockTiles.addAll(pinnedTiles());
        dockTiles.add(Tile.allApps());
        layout = DockLayout.compute(dockTiles.size(), availW, prefs.dockRows());
        LinearLayout row = null;
        for (int i = 0; i < dockTiles.size(); i++) {
            if (i % layout.cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) rl.topMargin = px(DockLayout.GAP - 2 * RING);
                dockBox.addView(row, rl);
            }
            View v = tileView(dockTiles.get(i), layout.tileW, layout.tileH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(layout.tileW + 2 * RING), px(layout.tileH + 2 * RING));
            if (i % layout.cols > 0) lp.leftMargin = px(DockLayout.GAP - 2 * RING);
            row.addView(v, lp);
            dockViews.add(v);
        }
        // Content rows shrink a little when the dock takes two rows, so everything clears the clock.
        contentW = layout.rows > 1 ? 144 : 176;
        contentH = Math.round(contentW * 9f / 16f);
        heroTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, layout.rows > 1 ? 24 : 30);
        placeAboveDock();
    }

    /** Height of the dock's tiles from the top of the first row to the bottom of the last, in dp. */
    private int dockHeight() {
        return layout.rows * layout.tileH + (layout.rows - 1) * DockLayout.GAP;
    }

    private void placeAboveDock() {
        int dockTop = BOTTOM + dockHeight();
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) contentScroll.getLayoutParams();
        cl.bottomMargin = px(dockTop + 24 - RING - VPAD);
        contentScroll.setLayoutParams(cl);
        FrameLayout.LayoutParams hl = (FrameLayout.LayoutParams) hero.getLayoutParams();
        hl.bottomMargin = px(dockTop + contentH + 66);
        hero.setLayoutParams(hl);
    }

    private void focusDock(int i) {
        main.removeCallbacks(afterLanding);
        if (dockViews.isEmpty()) return;
        zone = Zone.DOCK;
        styleGear(false);
        dockFocus = Math.max(0, Math.min(i, dockViews.size() - 1));
        for (int k = 0; k < dockViews.size(); k++) setFocused(dockViews.get(k), k == dockFocus, true);
        unfocusContent();
        scrollIntoView(dockScroll, dockViews.get(dockFocus));
        if (mode == Mode.HOME) onTileFocused(dockTiles.get(dockFocus));
    }

    private void focusGear() {
        zone = Zone.GEAR;
        markSpotTile();
        unfocusContent();
        styleGear(true);
    }

    private void unfocusContent() {
        for (View v : contentViews) {
            setFocused(v, false, false);
            caption(v).setTextColor(CAPTION);
        }
        dockScroll.animate().alpha(1f).setDuration(200).setInterpolator(Ui.EASE).start();
    }

    private static TextView caption(View contentTile) {
        return (TextView) ((ViewGroup) contentTile).getChildAt(1);
    }

    private void styleGear(boolean focused) {
        GradientDrawable disc = Ui.circle(focused ? 0xFFFFFFFF : 0x59000000);
        if (!focused) disc.setStroke(px(1), 0x33FFFFFF);
        gear.setBackground(disc);
        gear.setColorFilter(focused ? 0xFF111317 : 0xFFFFFFFF);
        gear.animate().scaleX(focused ? ZOOM : 1f).scaleY(focused ? ZOOM : 1f).setDuration(160).setInterpolator(Ui.EASE).start();
    }

    private void scrollIntoView(HorizontalScrollView s, View tile) {
        s.post(() -> {
            View row = (View) tile.getParent();
            int x = row.getLeft() + tile.getLeft() - s.getPaddingLeft();
            int w = s.getWidth() - s.getPaddingLeft() - s.getPaddingRight();
            int cur = s.getScrollX();
            if (x < cur) s.smoothScrollTo(x, 0);
            else if (x + tile.getWidth() > cur + w) s.smoothScrollTo(x + tile.getWidth() - w, 0);
        });
    }

    // ---- spotlight: what the focused tile shows above the dock ----

    private void onTileFocused(Tile t) {
        main.removeCallbacks(pendingSpot);
        main.removeCallbacks(pendingPreview);
        if (t.kind != Tile.Kind.INPUT || !t.inputId.equals(previewFor)) stopPreview();
        if (t.kind == Tile.Kind.APP) {
            if (!t.id.equals(spotFor) && spots.get(t.pkg) == null) fadeOutSpot();
            spotFor = t.id;
            if (!prefs.spotlight()) return;
            Spot cached = spots.get(t.pkg);
            if (cached != null) {
                applySpot(t, cached);
                if (SystemClock.uptimeMillis() - cached.at < 120_000) return;
            }
            final int token = ++spotToken;
            pendingSpot = () -> loadSpot(t, token);
            main.postDelayed(pendingSpot, cached != null ? 0 : 160);
        } else {
            spotFor = null;
            fadeOutSpot();
            if (t.kind == Tile.Kind.INPUT && prefs.preview() && !prefs.previewOff(t.inputId)) {
                // Only when focus rests on the tile: passing over it leaves the aerial alone.
                pendingPreview = () -> startPreview(t);
                main.postDelayed(pendingPreview, 700);
            }
        }
    }

    /**
     * Reads every pinned app's content in the background, so focusing a tile shows its row straight from memory:
     * the TV's content database is slow to query.
     */
    private void preloadSpots() {
        if (!prefs.spotlight()) return;
        List<Tile> apps = new ArrayList<>();
        for (Tile t : dockTiles) if (t.kind == Tile.Kind.APP) apps.add(t);
        io.execute(() -> {
            for (Tile t : apps) {
                List<Content.Source> sources = Content.sources(this, t.pkg);
                List<Content.Item> items = Content.items(this, t.pkg, sources, prefs.hiddenSources(t.pkg), 12);
                for (int i = 0; i < Math.min(items.size(), 6); i++) ImageCache.load(this, items.get(i).art, Math.round(contentW * dp));
                Spot s = new Spot(sources, items);
                main.post(() -> {
                    spots.put(t.pkg, s);
                    sourcesByPkg.put(t.pkg, sources);
                    if (mode == Mode.HOME && t.id.equals(spotFor) && dockFocus < dockTiles.size()
                            && dockTiles.get(dockFocus).id.equals(t.id)) applySpot(t, s);
                });
            }
        });
    }

    private void loadSpot(Tile t, int token) {
        io.execute(() -> {
            List<Content.Source> sources = Content.sources(this, t.pkg);
            List<Content.Item> items = Content.items(this, t.pkg, sources, prefs.hiddenSources(t.pkg), 12);
            Spot s = new Spot(sources, items);
            main.post(() -> {
                spots.put(t.pkg, s);
                sourcesByPkg.put(t.pkg, sources);
                if (token == spotToken && mode != Mode.DRAWER && t.id.equals(spotFor)) applySpot(t, s);
            });
        });
    }

    /**
     * Shows an app's content row. Moving between two apps crossfades: the old row and title fade out, the new
     * ones fade in, instead of the row being swapped in a single frame.
     */
    private void applySpot(Tile t, Spot s) {
        if (s.items.isEmpty()) {
            boolean wasInRow = zone == Zone.CONTENT;
            fadeOutSpot();
            if (wasInRow) for (int k = 0; k < dockViews.size(); k++) setFocused(dockViews.get(k), k == dockFocus, true);
            return;
        }
        boolean showing = contentScroll.getVisibility() == View.VISIBLE && shownFor != null;
        if (showing && t.id.equals(shownFor)) {
            if (!sameItems(s.items)) fillSpot(t, s);
            return;
        }
        int gen = ++spotGen;
        if (showing) {
            contentScroll.animate().cancel();
            hero.animate().cancel();
            hero.animate().alpha(0f).setDuration(110).start();
            contentScroll.animate().alpha(0f).translationY(px(-6)).setDuration(110).setInterpolator(Ui.EASE)
                    .withEndAction(() -> {
                        if (gen != spotGen) return;
                        fillSpot(t, s);
                        revealSpot(gen);
                    }).start();
        } else {
            fillSpot(t, s);
            contentScroll.setVisibility(View.VISIBLE);
            contentScroll.setAlpha(0f);
            hero.setAlpha(0f);
            revealSpot(gen);
        }
        backShade.animate().alpha(0.85f).setDuration(300).start();
    }

    private void fillSpot(Tile t, Spot s) {
        shownFor = t.id;
        contentItems.clear();
        contentItems.addAll(s.items);
        renderContent();
        heroIcon.setImageDrawable(appIcon(t.pkg));
        showHeroFor(zone == Zone.CONTENT ? contentFocus : 0);
        if (zone == Zone.CONTENT) focusContent(Math.min(contentFocus, contentViews.size() - 1));
    }

    private void revealSpot(int gen) {
        if (gen != spotGen) return;
        contentScroll.setTranslationY(px(10));
        contentScroll.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(Ui.EASE).start();
        hero.animate().alpha(1f).setDuration(220).start();
    }

    /** Fades the content row away (for a tile with no content), then clears it. */
    private void fadeOutSpot() {
        if (shownFor == null && contentScroll.getVisibility() != View.VISIBLE) return;
        int gen = ++spotGen;
        shownFor = null;
        if (zone == Zone.CONTENT) zone = Zone.DOCK;
        hero.animate().cancel();
        hero.animate().alpha(0f).setDuration(160).start();
        contentScroll.animate().cancel();
        contentScroll.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (gen == spotGen) clearSpot();
        }).start();
        hideBackdrop();
    }

    private boolean sameItems(List<Content.Item> items) {
        if (items.size() != contentItems.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            Content.Item a = items.get(i), b = contentItems.get(i);
            if (!a.title.equals(b.title) || !TextUtils.equals(a.art, b.art)) return false;
        }
        return true;
    }

    private void showHeroFor(int i) {
        if (i >= contentItems.size()) return;
        Content.Item it = contentItems.get(i);
        heroTitle.setText(it.title);
        heroSub.setText(it.sub != null ? it.sub : "");
        heroSub.setVisibility(it.sub != null && layout.rows == 1 ? View.VISIBLE : View.GONE);
        backShade.animate().alpha(0.85f).setDuration(400).start();
    }

    private void clearSpot() {
        spotGen++;
        shownFor = null;
        contentItems.clear();
        contentViews.clear();
        contentRow.removeAllViews();
        contentScroll.animate().cancel();
        contentScroll.setVisibility(View.GONE);
        hero.animate().cancel();
        hero.setAlpha(0f);
        hideBackdrop();
        if (zone == Zone.CONTENT) zone = Zone.DOCK;
    }

    private void renderContent() {
        contentRow.removeAllViews();
        contentViews.clear();
        for (int i = 0; i < contentItems.size(); i++) {
            View v = contentTile(contentItems.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = px(14 - 2 * RING);
            contentRow.addView(v, lp);
            contentViews.add(v);
        }
        contentScroll.scrollTo(0, 0);
    }

    private View contentTile(Content.Item it) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout outer = new FrameLayout(this);
        outer.setPadding(px(RING), px(RING), px(RING), px(RING));
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.rounded(Ui.CARD, px(Ui.RADIUS)));
        Ui.clip(card, px(Ui.RADIUS));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, full());
        if (it.progress > 0.02f) {
            View track = new View(this);
            track.setBackground(Ui.rounded(0x40FFFFFF, px(1.5f)));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(px(contentW - 20), px(3), Gravity.BOTTOM | Gravity.START);
            tl.leftMargin = px(10);
            tl.bottomMargin = px(8);
            card.addView(track, tl);
            View bar = new View(this);
            bar.setBackground(Ui.rounded(0xFFFFFFFF, px(1.5f)));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(
                    Math.max(px(3), Math.round(px(contentW - 20) * it.progress)), px(3), Gravity.BOTTOM | Gravity.START);
            bl.leftMargin = px(10);
            bl.bottomMargin = px(8);
            card.addView(bar, bl);
        }
        outer.addView(card, new FrameLayout.LayoutParams(px(contentW), px(contentH)));
        col.addView(outer, new LinearLayout.LayoutParams(px(contentW + 2 * RING), px(contentH + 2 * RING)));
        TextView cap = Ui.text(this, Ui.medium(), 13, CAPTION);
        cap.setText(it.title);
        cap.setSingleLine(true);
        cap.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams capl = new LinearLayout.LayoutParams(px(contentW), -2);
        capl.leftMargin = px(RING);
        capl.topMargin = px(6);
        col.addView(cap, capl);
        Bitmap ready = ImageCache.peek(it.art, contentW * 2);
        if (ready != null) {
            img.setImageBitmap(ready);
        } else if (it.art != null) {
            String want = it.art;
            img.setTag(want);
            io.execute(() -> {
                Bitmap b = ImageCache.load(this, want, Math.round(contentW * dp));
                main.post(() -> {
                    if (b != null && want.equals(img.getTag())) {
                        img.setAlpha(0f);
                        img.setImageBitmap(b);
                        img.animate().alpha(1f).setDuration(200).start();
                    }
                });
            });
        }
        return col;
    }

    private void focusContent(int i) {
        if (contentViews.isEmpty()) return;
        zone = Zone.CONTENT;
        styleGear(false);
        contentFocus = Math.max(0, Math.min(i, contentViews.size() - 1));
        markSpotTile();
        for (int k = 0; k < contentViews.size(); k++) {
            setFocused(contentViews.get(k), k == contentFocus, false);
            caption(contentViews.get(k)).setTextColor(k == contentFocus ? Ui.TEXT : CAPTION);
        }
        dockScroll.animate().alpha(0.5f).setDuration(200).setInterpolator(Ui.EASE).start();
        View col = contentViews.get(contentFocus);
        contentScroll.post(() -> {
            int x = col.getLeft();
            int w = contentScroll.getWidth() - contentScroll.getPaddingLeft() - contentScroll.getPaddingRight();
            int cur = contentScroll.getScrollX();
            if (x < cur) contentScroll.smoothScrollTo(x, 0);
            else if (x + col.getWidth() > cur + w) contentScroll.smoothScrollTo(x + col.getWidth() - w, 0);
        });
        showHeroFor(contentFocus);
    }

    private void openContent(Content.Item it, Tile app) {
        Intent i = null;
        if (it.intent != null) {
            try {
                i = it.intent.startsWith("intent:") ? Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(it.intent)).setPackage(app.pkg);
            } catch (Exception e) {
                Log.w(TAG, "bad content intent: " + e);
            }
        }
        if (i == null) i = app.launch;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!startSafe(i)) startSafe(app.launch);
    }

    // ---- backdrop ----

    /** Darkens the left and bottom behind the content row so its titles read over the aerial. */
    private void hideBackdrop() {
        backShade.animate().alpha(0f).setDuration(300).start();
    }


    // ---- input preview ----
    //
    // An input's picture and the aerial both need the TV's video planes, and the TV always draws the aerial over the
    // input. Starting or stopping an input also makes the TV blank its video for a moment. So the input's session
    // is opened once, when the home appears and before the aerial takes a plane, and kept running muted in a
    // one-pixel window under the aerial. Focusing its tile then only freezes the aerial on a blurred copy and opens
    // the box onto the picture that is already live; leaving reverses that, and the session never stops while the
    // home is up.

    private String warmFor;                 // input whose session is open
    private boolean warmVideo;              // ...and has a picture
    private int previewGen;                 // bumps on every enter/leave, so stale steps do nothing
    private static final long REVEAL_MS = 400, COVER_MS = 250, SETTLE_MS = 400;

    private boolean previewable(Tile t) {
        if (t.kind != Tile.Kind.INPUT || !prefs.preview() || prefs.previewOff(t.inputId)) return false;
        android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) getSystemService(TV_INPUT_SERVICE);
        return tim != null && Catalog.state(tim, t.inputId) == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
    }

    /** The pinned input to keep warm, or null. */
    private Tile warmCandidate() {
        for (Tile t : dockTiles) if (previewable(t)) return t;
        return null;
    }

    private void ensureTvView() {
        if (tvView != null) return;
        tvView = new TvView(this);
        tvView.setFocusable(false);
        tvView.setZOrderMediaOverlay(true);
        tvView.setStreamVolume(0f);         // a picture only: the input's sound must not play on the home
        tvView.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String inputId) {
                Log.i(TAG, "input video available");
                if (!inputId.equals(warmFor)) return;
                warmVideo = true;
                aerial.setHeld(false);
                if (inputId.equals(previewFor) && previewMask.getAlpha() > 0f) {
                    int gen = previewGen;
                    main.postDelayed(() -> reveal(gen), SETTLE_MS);
                }
            }

            @Override public void onVideoSizeChanged(String inputId, int w, int h) {
                if (w > 0 && h > 0 && inputId.equals(previewFor)) sizePreview(w / (float) h);
            }

            @Override public void onConnectionFailed(String inputId) {
                Log.i(TAG, "input connection failed");
                lostInput();
            }

            @Override public void onDisconnected(String inputId) {
                Log.i(TAG, "input disconnected");
                lostInput();
            }
        });
        previewFrame.addView(tvView, 0, pixel());
    }

    private static FrameLayout.LayoutParams pixel() {
        return new FrameLayout.LayoutParams(1, 1);
    }

    /** Opens the input's session in the one-pixel window. The TV's blank, if any, happens now, unseen. */
    private void warm(Tile t) {
        ensureTvView();
        warmFor = t.inputId;
        warmVideo = false;
        sizePreview(16f / 9f);
        tvView.setLayoutParams(pixel());
        previewMask.setAlpha(0f);
        previewFrame.setVisibility(View.VISIBLE);
        Log.i(TAG, "input warm " + t.inputId);
        tvView.tune(t.inputId, TvContract.buildChannelUriForPassthroughInput(t.inputId));
        main.postDelayed(() -> aerial.setHeld(false), 2500);     // in case the input never shows a picture
    }

    /** Closes the input's session (leaving the home, or switching to the input for real). */
    private void cool() {
        previewGen++;
        previewFor = null;
        warmFor = null;
        warmVideo = false;
        if (tvView != null) {
            try {
                tvView.reset();
            } catch (Exception ignored) {
            }
            tvView.setLayoutParams(pixel());
        }
        previewFrame.setVisibility(View.GONE);
        previewCover.setImageBitmap(null);
    }

    private void lostInput() {
        warmFor = null;
        warmVideo = false;
        aerial.setHeld(false);
        if (previewFor != null) {
            previewMask.animate().cancel();
            previewMask.animate().alpha(1f).setDuration(COVER_MS).start();
        }
    }

    /** Focus rests on an input tile: blur the aerial, open the box onto the input's picture. */
    private void startPreview(Tile t) {
        if (mode != Mode.HOME || zone != Zone.DOCK || !previewable(t)) return;
        if (t.inputId.equals(previewFor)) return;
        int gen = ++previewGen;
        previewFor = t.inputId;
        ensureTvView();
        boolean warm = t.inputId.equals(warmFor);
        Runnable open = () -> {
            if (gen != previewGen) return;
            // The box opens filled with the same blurred, shaded frame as around it, so it is invisible at first.
            previewMask.animate().cancel();
            previewCover.setImageBitmap(aerial.softFrame());
            sizePreview(16f / 9f);
            previewMask.setAlpha(1f);
            tvView.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            previewFrame.setVisibility(View.VISIBLE);
            if (warm) {
                aerial.hidePlane();
                if (warmVideo) main.postDelayed(() -> reveal(gen), 80);
            } else {
                aerial.releasePlane();
                warmFor = t.inputId;            // first visit: open it now, it stays open after
                warmVideo = false;
                Log.i(TAG, "input tune " + t.inputId);
                tvView.tune(t.inputId, TvContract.buildChannelUriForPassthroughInput(t.inputId));
            }
        };
        if (warm) aerial.blurOver(open);
        else aerial.freeze(open);
    }

    private void reveal(int gen) {
        if (gen != previewGen || previewFor == null) return;
        previewMask.animate().cancel();
        previewMask.animate().alpha(0f).setStartDelay(0).setDuration(REVEAL_MS).start();
    }

    /**
     * Focus left the input tile: the box melts back into the blurred frame, the aerial grows back under it on the
     * same frame, and the blur fades off the real video. The input's session stays open behind it.
     */
    private void stopPreview() {
        main.removeCallbacks(pendingPreview);
        if (previewFor == null) return;
        int gen = ++previewGen;
        previewFor = null;
        if (!aerial.isFrozen()) return;
        Runnable back = () -> {
            if (gen != previewGen) return;
            if (aerial.isPlaneHidden() || !aerial.isStillUp()) {
                aerial.unblurOff(() -> {
                    if (gen != previewGen) return;
                    // The aerial is back over the input: the box only shows it now, so it can go.
                    if (tvView != null) tvView.setLayoutParams(pixel());
                    previewMask.setAlpha(0f);
                });
            } else {
                aerial.restore(() -> {
                    if (gen != previewGen) return;
                    if (tvView != null) tvView.setLayoutParams(pixel());
                    previewMask.setAlpha(0f);
                    aerial.unblur(() -> {
                        if (gen == previewGen) aerial.thaw();
                    });
                });
            }
        };
        if (previewFrame.getVisibility() != View.VISIBLE || tvView == null
                || tvView.getLayoutParams().width <= 1) {
            back.run();                     // left before the box opened
            return;
        }
        previewMask.animate().cancel();
        previewMask.animate().alpha(1f).setStartDelay(0).setDuration(COVER_MS).withEndAction(back).start();
    }

    /** Fits the preview to the input's aspect ratio in the space between the clock and the dock. */
    private void sizePreview(float ratio) {
        int top = TOP + 84, bottom = BOTTOM + dockHeight() + 28;
        int h = screenHdp - top - bottom;
        int w = Math.round(h * ratio);
        if (w > availW) {
            w = availW;
            h = Math.round(w / ratio);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewFrame.getLayoutParams();
        lp.width = px(w);
        lp.height = px(h);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = px(top + (screenHdp - top - bottom - h) / 2);
        previewFrame.setLayoutParams(lp);
        alignCover();
    }

    /** Places the cover's blurred frame so it lines up with the same pixels in the background. */
    private void alignCover() {
        if (root.getWidth() == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewFrame.getLayoutParams();
        android.graphics.drawable.Drawable d = previewCover.getDrawable();
        if (d != null && d.getIntrinsicWidth() > 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(root.getWidth() / (float) d.getIntrinsicWidth(), root.getHeight() / (float) d.getIntrinsicHeight());
            m.postTranslate(-(root.getWidth() - lp.width) / 2f, -lp.topMargin);
            previewCover.setImageMatrix(m);
        }
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(root.getWidth(), root.getHeight());
        previewShade.setLayoutParams(sl);
        previewShade.setTranslationX(-(root.getWidth() - lp.width) / 2f);
        previewShade.setTranslationY(-lp.topMargin);
    }

    // ---- all apps ----

    private void openDrawer() {
        openDrawer(-1);
    }

    /** {@code from}: the dock tile Down was pressed on, to land on the app below it; -1 lands on the first app. */
    private void openDrawer(int from) {
        mode = Mode.DRAWER;
        stopPreview();
        main.removeCallbacks(pendingSpot);
        main.removeCallbacks(afterLanding);
        spotFor = null;
        renderGrid();
        drawerScroll.scrollTo(0, 0);
        for (View v : new View[]{drawerBg, drawerHeader}) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
        }
        drawerScroll.setVisibility(View.VISIBLE);
        drawerScroll.setAlpha(1f);
        for (View row : gridRows) {
            row.setAlpha(0f);
            row.setTranslationY(px(24));
        }
        drawerFocus = gridTiles.isEmpty() ? dockTiles.size() - 1 : from >= 0 ? gridTileBelow(from) : dockTiles.size();
        // The new grid is laid out and drawn in the coming frame. Moving only after that frame keeps its cost from
        // swallowing the start of the motion, which made the dock jump.
        int gen = ++drawerGen;
        root.postOnAnimation(() -> root.postOnAnimation(() -> {
            if (gen != drawerGen) return;
            focusDrawer(drawerFocus);
            fadeOutSpot();
            drawerBg.animate().alpha(1f).setStartDelay(0).setDuration(400).start();
            drawerHeader.animate().alpha(1f).setStartDelay(320).setDuration(300).start();
            for (View v : new View[]{clock, date, gear}) v.animate().alpha(0f).setStartDelay(0).setDuration(200).start();
            dockScroll.animate().alpha(1f).translationY(liftedDockY()).setStartDelay(0).setDuration(LIFT_MS)
                    .setInterpolator(Ui.GLIDE).start();
            for (int r = 0; r < gridRows.size(); r++) {
                // Each row comes in once the rising bar has passed it, so tiles never cross.
                gridRows.get(r).animate().alpha(1f).translationY(0).setStartDelay(320 + 40L * Math.min(r, 8))
                        .setDuration(380).setInterpolator(Ui.EASE).start();
            }
        }));
    }

    // The bar and the grid size their tiles differently, so moving between them goes by position, not column number.

    /** The first-row app nearest below dock tile {@code i}. */
    private int gridTileBelow(int i) {
        float x = i % layout.cols * (layout.tileW + DockLayout.GAP) + layout.tileW / 2f - dockScroll.getScrollX() / dp;
        return dockTiles.size() + Math.min(nearestCol(x, gridW, gridCols), gridTiles.size() - 1);
    }

    /** The tile in the bar's last row nearest above grid column {@code g}. */
    private int barTileAbove(int g) {
        int last = (dockTiles.size() - 1) / layout.cols * layout.cols;
        float x = g * (gridW + DockLayout.GAP) + gridW / 2f + dockScroll.getScrollX() / dp;
        return last + nearestCol(x, layout.tileW, dockTiles.size() - last);
    }

    /** The column, of {@code cols} tiles {@code w} dp wide, whose centre is nearest {@code x} dp. */
    private static int nearestCol(float x, int w, int cols) {
        return Math.max(0, Math.min(cols - 1, Math.round((x - w / 2f) / (w + DockLayout.GAP))));
    }

    /** How far the dock moves up to become the drawer's bar, in px (negative). */
    private float liftedDockY() {
        int restingTop = screenHdp - BOTTOM - dockHeight();
        int liftedTop = TOP + HEADER;
        return px(liftedTop - restingTop);
    }

    private void closeDrawer() {
        closeDrawer(dockTiles.size() - 1);
    }

    private void closeDrawer(int focus) {
        drawerGen++;
        drawerBg.animate().alpha(0f).setStartDelay(0).setDuration(450).withEndAction(() -> drawerBg.setVisibility(View.GONE)).start();
        drawerHeader.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction(() -> drawerHeader.setVisibility(View.GONE)).start();
        drawerScroll.animate().alpha(0f).setStartDelay(0).setDuration(180).withEndAction(() -> drawerScroll.setVisibility(View.GONE)).start();
        for (View v : new View[]{clock, date, gear}) v.animate().alpha(1f).setStartDelay(250).setDuration(300).start();
        dockScroll.animate().translationY(0).setStartDelay(0).setDuration(LIFT_MS).setInterpolator(Ui.GLIDE).start();
        for (View v : gridViews) setFocused(v, false, true);
        focusDock(focus);                   // still DRAWER: the ring only, the tile's content waits for the landing
        mode = Mode.HOME;
        main.postDelayed(afterLanding, LIFT_MS - 120);
    }

    private final Runnable afterLanding = () -> {
        if (resumed && mode == Mode.HOME && zone == Zone.DOCK && dockFocus < dockTiles.size()) {
            onTileFocused(dockTiles.get(dockFocus));
        }
    };

    /** Leaves the drawer instantly (when returning to the home from elsewhere). */
    private void resetDrawer() {
        drawerGen++;
        for (View v : new View[]{drawerBg, drawerHeader, drawerScroll}) {
            v.animate().cancel();
            v.setVisibility(View.GONE);
        }
        for (View v : new View[]{clock, date, gear}) {
            v.animate().cancel();
            v.setAlpha(1f);
        }
        dockScroll.animate().cancel();
        dockScroll.setTranslationY(0);
        dockScroll.setAlpha(1f);
    }

    private void renderGrid() {
        drawerGrid.removeAllViews();
        gridTiles.clear();
        gridViews.clear();
        gridRows.clear();
        List<Tile> rest = new ArrayList<>();
        for (Tile t : catalog) if (!pins.isPinned(t.id)) rest.add(t);
        gridTiles.addAll(gridOrder.order(rest, t -> t.id));
        if (layout.rows == 1 && layout.cols >= 5) {
            gridCols = layout.cols;
            gridW = layout.tileW;
        } else {
            gridCols = Math.max(1, (availW + DockLayout.GAP) / (120 + DockLayout.GAP));
            gridW = (availW - (gridCols - 1) * DockLayout.GAP) / gridCols;
        }
        gridH = Math.round(gridW * 9f / 16f);

        TextView label = Ui.text(this, Ui.semibold(), 12, Ui.TEXT_DIM);
        label.setLetterSpacing(0.16f);
        label.setText("APPS & INPUTS NOT ON YOUR HOME");
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.leftMargin = px(RING);
        ll.bottomMargin = px(10);
        drawerGrid.addView(label, ll);
        gridRows.add(label);

        LinearLayout row = null;
        for (int i = 0; i < gridTiles.size(); i++) {
            if (i % gridCols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                // Fade each tile, not the row: a fading row draws through a buffer that cuts off the focus ring.
                row.forceHasOverlappingRendering(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) rl.topMargin = px(DockLayout.GAP + 6 - 2 * RING);
                drawerGrid.addView(row, rl);
                gridRows.add(row);
            }
            View v = tileView(gridTiles.get(i), gridW, gridH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(gridW + 2 * RING), px(gridH + 2 * RING));
            if (i % gridCols > 0) lp.leftMargin = px(DockLayout.GAP - 2 * RING);
            row.addView(v, lp);
            gridViews.add(v);
        }
        FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) drawerScroll.getLayoutParams();
        sl.topMargin = px(TOP + HEADER + dockHeight() + 22 - VPAD);
        drawerScroll.setLayoutParams(sl);
    }

    private GridNav drawerNav() {
        return new GridNav(dockTiles.size(), layout.cols, gridTiles.size(), gridCols);
    }

    private void focusDrawer(int i) {
        int bar = dockTiles.size();
        drawerFocus = Math.max(0, Math.min(i, bar + gridTiles.size() - 1));
        for (int k = 0; k < dockViews.size(); k++) setFocused(dockViews.get(k), k == drawerFocus, true);
        for (int k = 0; k < gridViews.size(); k++) setFocused(gridViews.get(k), k + bar == drawerFocus, true);
        if (drawerFocus < bar) scrollIntoView(dockScroll, dockViews.get(drawerFocus));
        else scrollDrawerTo(gridViews.get(drawerFocus - bar));
    }

    private void scrollDrawerTo(View tile) {
        View row = (View) tile.getParent();
        drawerScroll.post(() -> {
            int y = row.getTop() - px(VPAD), h = drawerScroll.getHeight() - px(48);
            int cur = drawerScroll.getScrollY();
            if (y < cur) drawerScroll.smoothScrollTo(0, y);
            else if (y + row.getHeight() > cur + h) drawerScroll.smoothScrollTo(0, y + row.getHeight() - h);
        });
    }

    private Tile drawerTile(int i) {
        return i < dockTiles.size() ? dockTiles.get(i) : gridTiles.get(i - dockTiles.size());
    }

    // ---- keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        if (ok) {
            if (down) {
                boolean longPress = (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
                if (okKey.onDown(e.getRepeatCount(), longPress, e.getEventTime())) {
                    main.removeCallbacks(holdTimer);
                    onHold();
                } else if (e.getRepeatCount() == 0 && !longPress && mode != Mode.MENU) {
                    main.removeCallbacks(holdTimer);
                    main.postDelayed(holdTimer, OkKey.HOLD_MS);
                }
            } else {
                main.removeCallbacks(holdTimer);
                if (okKey.onUp() && !(mode == Mode.MENU && SystemClock.uptimeMillis() - menuOpenedAt < 300)) onSelect();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (!down && !e.isCanceled()) onBack();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (down && e.getRepeatCount() == 0) onHold();
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);
        boolean repeat = e.getRepeatCount() > 0;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: nav(-1, 0, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: nav(1, 0, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_UP: nav(0, -1, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: nav(0, 1, repeat); return true;
            default: return super.dispatchKeyEvent(e);
        }
    }

    /** {@code repeat}: the key is held. A held key never opens or closes All apps, it stops at the edge. */
    private void nav(int dx, int dy, boolean repeat) {
        if (okKey.pressing()) okKey.reset();
        main.removeCallbacks(holdTimer);
        if (mode == Mode.MENU) {
            if (dy != 0) focusMenu(menuFocus + dy);
            return;
        }
        if (mode == Mode.MOVE) {
            int cols = moveInGrid ? gridCols : layout.rows > 1 ? layout.cols : 0;
            if (dx != 0) moveBy(dx);
            else if (cols > 0) moveBy(dy * cols);
            return;
        }
        if (mode == Mode.DRAWER) {
            int bar = dockTiles.size(), g = drawerFocus - bar;
            // The bar is the home's own row: going back up to it goes home.
            if (dy < 0 && g >= 0 && g < gridCols) {
                if (!repeat) closeDrawer(barTileAbove(g));
                return;
            }
            int to = drawerNav().move(drawerFocus, dx, dy);
            if (to == GridNav.OUT_TOP) return;
            if (to < bar && to != drawerFocus) {
                if (!repeat) closeDrawer(to);
            } else {
                focusDrawer(to);
            }
            return;
        }
        switch (zone) {
            case GEAR:
                if (dy > 0) {
                    if (!contentViews.isEmpty()) focusContent(contentFocus);
                    else focusDock(dockFocus);
                } else if (dx < 0 && !contentViews.isEmpty()) {
                    focusContent(contentFocus);
                }
                break;
            case CONTENT:
                if (dx != 0) focusContent(contentFocus + dx);
                else if (dy < 0) focusGear();
                else if (dy > 0) focusDock(dockFocus);
                break;
            default:
                int to = new GridNav(dockTiles.size(), layout.cols).move(dockFocus, dx, dy);
                if (to == GridNav.OUT_TOP) {
                    if (!contentViews.isEmpty()) focusContent(0);
                    else focusGear();
                } else if (to != dockFocus) {
                    focusDock(to);
                } else if (dy > 0 && !repeat) {
                    openDrawer(dockFocus);
                }
        }
    }

    private void onSelect() {
        if (mode == Mode.MOVE) {
            endMove();
            return;
        }
        if (mode == Mode.MENU) {
            if (menuFocus >= menuItems.size()) return;
            MenuItem m = menuItems.get(menuFocus);
            if (!m.stay) closeMenu();
            m.action.run();
            return;
        }
        if (mode == Mode.HOME && zone == Zone.GEAR) {
            startSafe(new Intent(this, SettingsActivity.class));
            return;
        }
        if (mode == Mode.HOME && zone == Zone.CONTENT) {
            Tile app = dockTiles.get(dockFocus);
            if (contentFocus < contentItems.size() && app.kind == Tile.Kind.APP) openContent(contentItems.get(contentFocus), app);
            return;
        }
        Tile t = mode == Mode.HOME ? dockTiles.get(dockFocus) : drawerTile(drawerFocus);
        switch (t.kind) {
            case ALL_APPS:
                if (mode == Mode.HOME) openDrawer();
                else closeDrawer();
                break;
            case INPUT:
                cool();             // the TV app needs the input
                openInput(t);
                break;
            default:
                startSafe(t.launch);
        }
    }

    private void onHold() {
        if (mode == Mode.MOVE) {
            endMove();
            return;
        }
        if (mode == Mode.MENU) return;
        if (mode == Mode.HOME && zone != Zone.DOCK) return;
        Tile t = mode == Mode.HOME ? dockTiles.get(dockFocus) : drawerTile(drawerFocus);
        if (t.kind == Tile.Kind.APP || t.kind == Tile.Kind.INPUT) openTileMenu(t);
    }

    private void onBack() {
        if (mode == Mode.MOVE) endMove();
        else if (mode == Mode.MENU) closeMenu();
        else if (mode == Mode.DRAWER) closeDrawer();
        else if (zone != Zone.DOCK) focusDock(dockFocus);
    }

    // ---- hold menu ----

    private void openTileMenu(Tile t) {
        List<MenuItem> items = new ArrayList<>();
        boolean pinned = pins.isPinned(t.id);
        MenuItem toggle = new MenuItem(pinned ? "Remove from home" : "Add to home", null, false, false, () -> {
            pins.toggle(t.id);
            savePins();
            afterPinChange(t.id);
        });
        MenuItem hide = new MenuItem("Hide", null, false, false, () -> hideTile(t));
        if (!pinned) items.add(toggle);
        if (pinned ? dockTiles.size() > 2 : gridTiles.size() > 1) {
            items.add(new MenuItem("Move", null, false, false, () -> startMove(t)));
        }
        if (t.kind == Tile.Kind.APP) {
            List<Content.Source> known = sourcesByPkg.get(t.pkg);
            if (known != null && !known.isEmpty()) {
                items.add(new MenuItem("Choose what's shown", null, false, true, () -> openSourcesMenu(t)));
            }
            items.add(new MenuItem("App info", null, false, false, () -> startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + t.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
            if (pinned) items.add(toggle);
            else items.add(hide);
            if (canUninstall(t.pkg)) {
                items.add(new MenuItem("Uninstall", null, false, false, () -> startSafe(new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + t.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
            }
        } else {
            boolean off = prefs.previewOff(t.inputId);
            items.add(new MenuItem("Live preview", off ? "Off" : "On", false, true, () -> {
                prefs.setPreviewOff(t.inputId, !off);
                int keep = menuFocus;
                openTileMenu(t);
                focusMenu(keep);
            }));
            if (pinned) items.add(toggle);
            else items.add(hide);
        }
        openMenu(t, items);
        if (t.kind == Tile.Kind.APP && !sourcesByPkg.containsKey(t.pkg)) {
            // An app focused only in the drawer has not had its content read yet: add the entry once known.
            io.execute(() -> {
                List<Content.Source> sources = Content.sources(this, t.pkg);
                main.post(() -> {
                    sourcesByPkg.put(t.pkg, sources);
                    if (mode == Mode.MENU && !sources.isEmpty()) {
                        int keep = menuFocus;
                        openTileMenu(t);
                        focusMenu(keep);
                    }
                });
            });
        }
    }

    private void openSourcesMenu(Tile t) {
        List<Content.Source> sources = sourcesByPkg.get(t.pkg);
        if (sources == null) return;
        Set<String> hidden = prefs.hiddenSources(t.pkg);
        List<MenuItem> items = new ArrayList<>();
        for (Content.Source src : sources) {
            boolean shown = !hidden.contains(src.key);
            items.add(new MenuItem(src.name, null, shown, true, () -> {
                Set<String> h = prefs.hiddenSources(t.pkg);
                if (!h.remove(src.key)) h.add(src.key);
                prefs.setHiddenSources(t.pkg, h);
                spots.remove(t.pkg);
                int keep = menuFocus;
                openSourcesMenu(t);
                focusMenu(keep);
            }));
        }
        openMenu(t, items);
    }

    private void afterPinChange(String id) {
        if (menuReturn == Mode.DRAWER) {
            // Added from All apps: it now sits in the bar, which is the home's row, so go home onto it.
            renderDock();
            dockScroll.animate().cancel();
            dockScroll.setTranslationY(liftedDockY());
            int at = indexOf(dockTiles, id);
            closeDrawer(at >= 0 ? at : dockTiles.size() - 1);
        } else {
            renderDock();
            focusDock(Math.max(0, Math.min(dockFocus, dockTiles.size() - 1)));
        }
    }

    private void openMenu(Tile t, List<MenuItem> items) {
        if (mode != Mode.MENU) menuReturn = mode;
        mode = Mode.MENU;
        main.removeCallbacks(holdTimer);
        menuItems.clear();
        menuItems.addAll(items);
        menuViews.clear();
        menuBox.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(12), px(10), px(12), px(14));
        ImageView icon = new ImageView(this);
        Drawable d = t.kind == Tile.Kind.APP ? appIcon(t.pkg) : getDrawable(R.drawable.ic_input);
        icon.setImageDrawable(d);
        if (t.kind != Tile.Kind.APP) icon.setColorFilter(Ui.TEXT);
        head.addView(icon, new LinearLayout.LayoutParams(px(30), px(30)));
        TextView name = Ui.text(this, Ui.semibold(), 17, Ui.TEXT);
        name.setText(t.label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
        nl.leftMargin = px(12);
        head.addView(name, nl);
        menuBox.addView(head);

        for (MenuItem m : items) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(14), 0, px(14), 0);
            TextView label = Ui.text(this, Ui.medium(), 15, Ui.TEXT);
            label.setText(m.label);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (m.value != null) {
                TextView v = Ui.text(this, Ui.regular(), 14, Ui.TEXT_DIM);
                v.setText(m.value);
                row.addView(v);
            }
            if (m.checked) {
                ImageView c = new ImageView(this);
                c.setImageResource(R.drawable.ic_check);
                row.addView(c, new LinearLayout.LayoutParams(px(20), px(20)));
            }
            menuBox.addView(row, new LinearLayout.LayoutParams(-1, px(46)));
            menuViews.add(row);
        }
        menuOpenedAt = SystemClock.uptimeMillis();
        menuScrim.animate().cancel();
        menuBox.animate().cancel();
        if (menuBox.getVisibility() != View.VISIBLE || menuBox.getAlpha() < 1f) {
            if (menuBox.getVisibility() != View.VISIBLE) {
                menuScrim.setAlpha(0f);
                menuBox.setAlpha(0f);
                menuBox.setScaleX(0.94f);
                menuBox.setScaleY(0.94f);
            }
            menuScrim.setVisibility(View.VISIBLE);
            menuBox.setVisibility(View.VISIBLE);
            menuScrim.animate().alpha(1f).setDuration(200).start();
            menuBox.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Ui.EASE).start();
        }
        focusMenu(0);
    }

    private void focusMenu(int i) {
        if (menuViews.isEmpty()) return;
        menuFocus = Math.max(0, Math.min(i, menuViews.size() - 1));
        for (int k = 0; k < menuViews.size(); k++) {
            boolean f = k == menuFocus;
            ViewGroup row = (ViewGroup) menuViews.get(k);
            row.setBackground(f ? Ui.rounded(0xFFFFFFFF, px(12)) : null);
            for (int c = 0; c < row.getChildCount(); c++) {
                View child = row.getChildAt(c);
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(f ? 0xFF111317 : (c == 0 ? Ui.TEXT : Ui.TEXT_DIM));
                } else if (child instanceof ImageView) {
                    ((ImageView) child).setColorFilter(f ? 0xFF111317 : Ui.ACCENT);
                }
            }
        }
    }

    private void closeMenu() {
        mode = menuReturn;
        menuScrim.animate().alpha(0f).setDuration(160).withEndAction(() -> menuScrim.setVisibility(View.GONE)).start();
        menuBox.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(140)
                .withEndAction(() -> menuBox.setVisibility(View.GONE)).start();
        if (mode == Mode.HOME && dockFocus < dockTiles.size()) {
            spots.remove(dockTiles.get(dockFocus).pkg);
            focusDock(dockFocus);
        }
    }

    private boolean canUninstall(String pkg) {
        try {
            int f = getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (f & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- moving a tile ----
    //
    // Move picks the tile up: it follows left and right (and up and down over several rows) until OK or Back puts
    // it down. It works on the dock and on All apps. Each step re-renders, then every tile glides from where it was
    // drawn to its new place.

    private static final float MOVE_DIM = 0.4f;

    private boolean moveInGrid;             // moving within All apps rather than the dock

    private List<Tile> moveTiles() {
        return moveInGrid ? gridTiles : dockTiles;
    }

    private List<View> moveViews() {
        return moveInGrid ? gridViews : dockViews;
    }

    /** The last place a moving tile can take: on the dock, All apps stays last. */
    private int moveLast() {
        return moveInGrid ? gridTiles.size() - 1 : dockTiles.size() - 2;
    }

    private void startMove(Tile t) {
        moveInGrid = mode == Mode.DRAWER;
        if (indexOf(moveTiles(), t.id) < 0) return;
        if (moveInGrid) {
            // All apps runs A to Z until the first move, then keeps the order shown; new apps go at the end.
            Pins order = new Pins(null);
            for (Tile g : gridTiles) order.pin(g.id);
            for (String id : gridOrder.ids()) order.pin(id);
            gridOrder = order;
            prefs.setGridOrder(order.serialize());
            dockScroll.animate().alpha(MOVE_DIM).setDuration(200).start();
        } else {
            main.removeCallbacks(pendingSpot);
            main.removeCallbacks(pendingPreview);
            stopPreview();
            spotToken++;
            spotFor = null;
            fadeOutSpot();
            dockFocus = indexOf(dockTiles, t.id);
        }
        mode = Mode.MOVE;
        movingId = t.id;
        markMoving(true);
    }

    private void moveBy(int steps) {
        List<Tile> tiles = moveTiles();
        int from = indexOf(tiles, movingId);
        int to = Math.max(0, Math.min(moveLast(), from + steps));
        if (from < 0 || to == from) return;
        if (glideFrom == null) {
            glideFrom = positions(moveViews(), tiles);
            root.getViewTreeObserver().addOnPreDrawListener(glide);
        }
        int step = to > from ? 1 : -1;
        if (moveInGrid) {
            for (int i = from; i != to; i += step) gridOrder.move(movingId, step, id -> indexOf(gridTiles, id) >= 0);
            prefs.setGridOrder(gridOrder.serialize());
            renderGrid();
            drawerFocus = dockTiles.size() + indexOf(gridTiles, movingId);
        } else {
            for (int i = from; i != to; i += step) pins.move(movingId, step, this::present);
            savePins();
            renderDock();
            dockFocus = indexOf(dockTiles, movingId);
            scrollIntoView(dockScroll, dockViews.get(dockFocus));
        }
        markMoving(false);
    }

    /** Dims every other tile and puts arrows on the moving one. */
    private void markMoving(boolean fade) {
        List<View> views = moveViews();
        int at = indexOf(moveTiles(), movingId);
        for (int k = 0; k < views.size(); k++) {
            setFocused(views.get(k), k == at, true);
            float a = k == at ? 1f : MOVE_DIM;
            if (fade) views.get(k).animate().alpha(a).setDuration(200).start();
            else views.get(k).setAlpha(a);
        }
        addArrows(fade);
    }

    private void endMove() {
        mode = moveInGrid ? Mode.DRAWER : Mode.HOME;
        movingId = null;
        for (View a : moveArrows) {
            a.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                if (a.getParent() instanceof ViewGroup) ((ViewGroup) a.getParent()).removeView(a);
            }).start();
        }
        moveArrows.clear();
        for (View v : moveViews()) v.animate().alpha(1f).setDuration(200).start();
        if (moveInGrid) {
            dockScroll.animate().alpha(1f).setDuration(200).start();
            focusDrawer(drawerFocus);
        } else {
            focusDock(dockFocus);
        }
    }

    /** Where each tile in a row-of-rows box is drawn now, by id, counting any glide still under way. */
    private static Map<String, float[]> positions(List<View> views, List<Tile> tiles) {
        Map<String, float[]> at = new HashMap<>();
        for (int k = 0; k < views.size(); k++) {
            View v = views.get(k), row = (View) v.getParent();
            at.put(tiles.get(k).id, new float[]{row.getLeft() + v.getLeft() + v.getTranslationX(),
                    row.getTop() + v.getTop() + v.getTranslationY()});
        }
        return at;
    }

    /** After a re-render is laid out and before it is drawn: tiles start where {@code from} saw them, then slide. */
    private static void slideFrom(Map<String, float[]> from, List<View> views, List<Tile> tiles) {
        for (int k = 0; k < views.size(); k++) {
            float[] was = from.get(tiles.get(k).id);
            if (was == null) continue;
            View v = views.get(k), row = (View) v.getParent();
            v.setTranslationX(was[0] - row.getLeft() - v.getLeft());
            v.setTranslationY(was[1] - row.getTop() - v.getTop());
            v.animate().translationX(0).translationY(0).setDuration(200).setInterpolator(Ui.EASE).start();
        }
    }

    private final ViewTreeObserver.OnPreDrawListener glide = new ViewTreeObserver.OnPreDrawListener() {
        @Override public boolean onPreDraw() {
            root.getViewTreeObserver().removeOnPreDrawListener(this);
            Map<String, float[]> from = glideFrom;
            glideFrom = null;
            if (from != null) slideFrom(from, moveViews(), moveTiles());
            int g = drawerFocus - dockTiles.size();
            if (mode == Mode.MOVE && moveInGrid && g >= 0 && g < gridViews.size()) scrollDrawerTo(gridViews.get(g));
            return true;
        }
    };

    // ---- hiding from All apps ----

    /** The tile fades, the rest of the grid glides into its place; Settings lists it to bring it back. */
    private void hideTile(Tile t) {
        int bar = dockTiles.size(), g = indexOf(gridTiles, t.id);
        if (g < 0) return;
        prefs.setHidden(t.id, true);
        gridViews.get(g).animate().alpha(0f).setStartDelay(0).setDuration(160).withEndAction(() -> {
            if (mode != Mode.DRAWER && !(mode == Mode.MENU && menuReturn == Mode.DRAWER)) return;
            int f = drawerFocus - bar;
            String keep = f >= 0 && f < gridTiles.size() ? gridTiles.get(f).id : null;
            catalog.removeIf(c -> c.id.equals(t.id));
            Map<String, float[]> before = positions(gridViews, gridTiles);
            renderGrid();
            drawerGrid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    drawerGrid.getViewTreeObserver().removeOnPreDrawListener(this);
                    slideFrom(before, gridViews, gridTiles);
                    return true;
                }
            });
            int k = indexOf(gridTiles, keep);
            focusDrawer(k >= 0 ? bar + k : gridTiles.isEmpty() ? bar - 1 : bar + Math.min(g, gridTiles.size() - 1));
        }).start();
    }

    /** Chevrons on the moving tile, on the sides it can still go. */
    private void addArrows(boolean fadeIn) {
        moveArrows.clear();
        int at = indexOf(moveTiles(), movingId);
        FrameLayout tile = (FrameLayout) moveViews().get(at);
        int size = (moveInGrid ? gridH : layout.tileH) < 60 ? 18 : 22;
        for (int side = -1; side <= 1; side += 2) {
            ImageView a = new ImageView(this);
            a.setImageResource(R.drawable.ic_chevron);
            a.setColorFilter(0xFF111317);
            a.setBackground(Ui.circle(0xF2FFFFFF));
            a.setPadding(px(3), px(3), px(3), px(3));
            a.setRotation(side < 0 ? 180f : 0f);
            boolean can = side < 0 ? at > 0 : at < moveLast();
            a.setVisibility(can ? View.VISIBLE : View.INVISIBLE);
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(px(size), px(size),
                    Gravity.CENTER_VERTICAL | (side < 0 ? Gravity.START : Gravity.END));
            l.leftMargin = l.rightMargin = px(size / 4);
            tile.addView(a, l);
            if (fadeIn) {
                a.setAlpha(0f);
                a.animate().alpha(1f).setDuration(200).start();
            }
            moveArrows.add(a);
        }
    }

    // ---- launching ----

    private boolean startSafe(Intent i) {
        if (i == null) return false;
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot start " + i + ": " + e);
            return false;
        }
    }

    /** Switches to an input; TVs that refuse the passthrough deep link get their TV app opened instead. */
    private void openInput(Tile t) {
        if (startSafe(t.launch)) return;
        startSafe(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.Channels.CONTENT_URI, "vnd.android.cursor.dir/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---- tiles ----

    private View tileView(Tile t, int wDp, int hDp) {
        FrameLayout outer = new FrameLayout(this);
        outer.setPadding(px(RING), px(RING), px(RING), px(RING));
        // The shadow follows the card, not the ring space around it.
        outer.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(px(RING), px(RING), v.getWidth() - px(RING), v.getHeight() - px(RING), px(Ui.RADIUS));
            }
        });
        FrameLayout card = new FrameLayout(this);
        Ui.clip(card, px(Ui.RADIUS));
        switch (t.kind) {
            case APP: {
                Drawable banner = appBanner(t.pkg);
                if (banner != null) {
                    card.setBackground(Ui.rounded(Ui.CARD, px(Ui.RADIUS)));
                    ImageView img = new ImageView(this);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    img.setImageDrawable(banner);
                    card.addView(img, full());
                } else {
                    int brand = brandColor(t.pkg);
                    card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{blend(brand, 0xFF2A2E36, 0.85f), 0xFF1C1F25}));
                    glyphAndLabel(card, appIcon(t.pkg), false, null, hDp);
                }
                break;
            }
            case INPUT:
                card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A3140, 0xFF1A1E27}));
                glyphAndLabel(card, getDrawable(R.drawable.ic_input), true, t.label, hDp);
                if (t.connected) {
                    View dot = new View(this);
                    dot.setBackground(Ui.circle(t.on ? Ui.ACCENT : 0x66FFFFFF));
                    FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(px(8), px(8), Gravity.TOP | Gravity.END);
                    dl.topMargin = px(9);
                    dl.rightMargin = px(9);
                    card.addView(dot, dl);
                }
                break;
            default:
                card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A3F4B, 0xFF262A33}));
                glyphAndLabel(card, getDrawable(R.drawable.ic_apps), true, null, hDp);
        }
        if (card.getForeground() == null) card.setForeground(Ui.stroke(0x14FFFFFF, px(1), px(Ui.RADIUS)));
        outer.addView(card, full());
        return outer;
    }

    /** A centred glyph, with a label under it only when {@code label} is set (inputs, which share one glyph). */
    private void glyphAndLabel(FrameLayout card, Drawable glyph, boolean tint, String label, int hDp) {
        boolean small = hDp < 64;
        if (label == null) {
            ImageView g = new ImageView(this);
            g.setImageDrawable(glyph);
            if (tint) g.setColorFilter(0xFFE8EAED);
            int size = Math.round(hDp * (tint ? 0.4f : 0.6f));
            card.addView(g, new FrameLayout.LayoutParams(px(size), px(size), Gravity.CENTER));
            return;
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        if (glyph != null) {
            ImageView g = new ImageView(this);
            g.setImageDrawable(glyph);
            int size = small ? 24 : 32;
            if (tint) {
                g.setColorFilter(0xFFE8EAED);
                int pad = small ? 3 : 4;
                g.setPadding(px(pad), px(pad), px(pad), px(pad));
            }
            col.addView(g, new LinearLayout.LayoutParams(px(size), px(size)));
        }
        TextView tv = Ui.text(this, Ui.medium(), small ? 12 : 13, Ui.TEXT);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = px(small ? 3 : 6);
        tl.leftMargin = tl.rightMargin = px(8);
        col.addView(tv, tl);
        card.addView(col, full());
    }

    /** Away from the dock, the tile whose content is showing keeps a faint ring so the row has an owner. */
    private void markSpotTile() {
        for (int k = 0; k < dockViews.size(); k++) {
            View v = dockViews.get(k);
            setFocused(v, false, true);
            if (k == dockFocus && !contentViews.isEmpty()) v.setForeground(Ui.stroke(0x66FFFFFF, px(2), px(Ui.RADIUS + RING - 1)));
        }
    }

    private void setFocused(View outer, boolean focused, boolean zoom) {
        float s = focused && zoom ? ZOOM : 1f;
        outer.animate().scaleX(s).scaleY(s).setDuration(180).setInterpolator(Ui.EASE).start();
        outer.setElevation(focused ? px(8) : 0);
        View ringHost = outer instanceof LinearLayout ? ((ViewGroup) outer).getChildAt(0) : outer;
        ringHost.setForeground(focused ? Ui.stroke(0xFFFFFFFF, px(3), px(Ui.RADIUS + RING - 1)) : null);
    }

    // ---- app art ----

    private Drawable appBanner(String pkg) {
        if (!banners.containsKey(pkg)) banners.put(pkg, loadBanner(pkg));
        return banners.get(pkg);
    }

    /** A fresh copy on every call: one drawable shown by two views would fight over its bounds. */
    private Drawable appIcon(String pkg) {
        if (!icons.containsKey(pkg)) icons.put(pkg, loadIcon(pkg));
        Drawable d = icons.get(pkg);
        Drawable.ConstantState s = d != null ? d.getConstantState() : null;
        return s != null ? s.newDrawable(getResources()) : d;
    }

    private Drawable loadBanner(String pkg) {
        try {
            Drawable b = getPackageManager().getApplicationBanner(pkg);
            if (b == null) {
                Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (i != null && i.getComponent() != null) b = getPackageManager().getActivityBanner(i.getComponent());
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private Drawable loadIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /** Loads every app's art in the background, so opening All apps does not stall reading it from each app. */
    private void prefetchArt() {
        List<String> todo = new ArrayList<>();
        for (Tile t : catalog) if (t.kind == Tile.Kind.APP && !banners.containsKey(t.pkg)) todo.add(t.pkg);
        if (todo.isEmpty()) return;
        io.execute(() -> {
            Map<String, Drawable> b = new HashMap<>(), ic = new HashMap<>();
            Map<String, Integer> c = new HashMap<>();
            for (String pkg : todo) {
                Drawable banner = loadBanner(pkg), icon = loadIcon(pkg);
                b.put(pkg, banner);
                ic.put(pkg, icon);
                c.put(pkg, colourOf(banner != null ? banner : icon));
            }
            main.post(() -> {
                for (String pkg : todo) {
                    if (!banners.containsKey(pkg)) banners.put(pkg, b.get(pkg));
                    if (!icons.containsKey(pkg)) icons.put(pkg, ic.get(pkg));
                    if (!brands.containsKey(pkg)) brands.put(pkg, c.get(pkg));
                }
            });
        });
    }

    /** The app's dominant saturated colour, from its banner or icon. */
    private int brandColor(String pkg) {
        Integer known = brands.get(pkg);
        if (known != null) return known;
        Drawable d = appBanner(pkg);
        int c = colourOf(d != null ? d : appIcon(pkg));
        brands.put(pkg, c);
        return c;
    }

    /** Draws a private copy, so it is safe off the main thread. */
    private static int colourOf(Drawable d) {
        int avg = 0xFF2A3942;
        if (d == null) return avg;
        try {
            Bitmap bmp = Bitmap.createBitmap(12, 7, Bitmap.Config.ARGB_8888);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, 12, 7);
            copy.draw(new Canvas(bmp));
            long r = 0, g = 0, b = 0, w = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 7; y++) for (int x = 0; x < 12; x++) {
                int c = bmp.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                int ww = 1 + (int) (hsv[1] * hsv[2] * 8);
                r += (long) Color.red(c) * ww;
                g += (long) Color.green(c) * ww;
                b += (long) Color.blue(c) * ww;
                w += ww;
            }
            bmp.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                hsv[1] = Math.max(hsv[1], 0.6f);
                hsv[2] = Math.min(Math.max(hsv[2], 0.55f), 0.85f);
                avg = Color.HSVToColor(hsv);
            }
        } catch (Exception ignored) {
        }
        return avg;
    }

    // ---- clock ----

    private void updateClock() {
        Date now = new Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
        main.removeCallbacks(tick);
        main.postDelayed(tick, 60_000 - (System.currentTimeMillis() % 60_000));
    }

    // ---- helpers ----

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private int px(float dpVal) {
        return Math.round(dpVal * dp);
    }
}
