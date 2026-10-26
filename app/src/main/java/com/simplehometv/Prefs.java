package com.simplehometv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The launcher's settings, all in one SharedPreferences file. */
final class Prefs {

    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    String pins() { return p.getString("pins", null); }
    void setPins(String csv) { p.edit().putString("pins", csv).apply(); }

    /** All apps' order once something was moved there, as ids; null until then, which means A to Z. */
    String gridOrder() { return p.getString("grid_order", null); }
    void setGridOrder(String csv) { p.edit().putString("grid_order", csv).apply(); }

    /** The app store package picked in Settings, or null to use the first one installed. */
    String store() { return p.getString("store", null); }
    void setStore(String pkg) { p.edit().putString("store", pkg).apply(); }

    /** {@link DockLayout#AUTO}, 1 or 2. */
    int dockRows() { return p.getInt("dock_rows", DockLayout.AUTO); }
    void setDockRows(int rows) { p.edit().putInt("dock_rows", rows).apply(); }

    boolean aerial() { return p.getBoolean("aerial", true); }
    void setAerial(boolean on) { p.edit().putBoolean("aerial", on).apply(); }

    /** Show an app's content (Continue watching, channels) when its tile is focused. */
    boolean spotlight() { return p.getBoolean("spotlight", true); }
    void setSpotlight(boolean on) { p.edit().putBoolean("spotlight", on).apply(); }

    /** Show a live picture when an input tile is focused. */
    boolean preview() { return p.getBoolean("preview", true); }
    void setPreview(boolean on) { p.edit().putBoolean("preview", on).apply(); }

    Set<String> aerialIds() {
        return p.contains("aerial_ids") ? set("aerial_ids") : new HashSet<>(Aerials.DEFAULT);
    }

    void setAerialIds(Set<String> ids) { putSet("aerial_ids", ids); }

    boolean aerialShuffle() { return p.getBoolean("aerial_shuffle", true); }
    void setAerialShuffle(boolean on) { p.edit().putBoolean("aerial_shuffle", on).apply(); }

    /** Null until chosen: the default then follows the screen. */
    Boolean aerial4k() { return p.contains("aerial_4k") ? p.getBoolean("aerial_4k", false) : null; }
    void setAerial4k(boolean on) { p.edit().putBoolean("aerial_4k", on).apply(); }

    /** The Home button service was on when last seen; a force-stop doesn't count as switching it off. */
    boolean homeButton() { return p.getBoolean("home_button", false); }
    void setHomeButton(boolean on) { p.edit().putBoolean("home_button", on).apply(); }

    /** Null until chosen: the default then follows the TV (see {@link AerialBackground#inWindow}). */
    Boolean aerialInWindow() { return p.contains("aerial_window") ? p.getBoolean("aerial_window", false) : null; }
    void setAerialInWindow(boolean on) { p.edit().putBoolean("aerial_window", on).apply(); }

    boolean screensaverClock() { return p.getBoolean("ss_clock", true); }
    void setScreensaverClock(boolean on) { p.edit().putBoolean("ss_clock", on).apply(); }

    /** The content sources ({@link Content.Source#key}) hidden from an app's spotlight. */
    Set<String> hiddenSources(String pkg) {
        return set("hide:" + pkg);
    }

    void setHiddenSources(String pkg, Set<String> keys) {
        putSet("hide:" + pkg, keys);
    }

    /** Ids ({@link Tile#id}) of the apps and inputs hidden from All apps. Pinned tiles show regardless. */
    Set<String> hiddenTiles() {
        return set("hidden");
    }

    void setHidden(String tileId, boolean hide) {
        Set<String> s = set("hidden");
        if (hide) s.add(tileId);
        else s.remove(tileId);
        putSet("hidden", s);
    }

    boolean previewOff(String inputId) {
        return set("nopreview").contains(inputId);
    }

    void setPreviewOff(String inputId, boolean off) {
        Set<String> s = set("nopreview");
        if (off) s.add(inputId);
        else s.remove(inputId);
        putSet("nopreview", s);
    }

    // Stored as text: StringSet values must never be mutated in place, which is an easy trap.
    private Set<String> set(String key) {
        String v = p.getString(key, "");
        Set<String> out = new HashSet<>();
        if (!v.isEmpty()) out.addAll(Arrays.asList(v.split("\n")));
        return out;
    }

    private void putSet(String key, Set<String> s) {
        p.edit().putString(key, String.join("\n", s)).apply();
    }
}
