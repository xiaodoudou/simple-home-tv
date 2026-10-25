package com.simplehometv;

import android.content.Intent;

/** One tile on the home: an app, a TV input (HDMI/AV/tuner), or the All apps button. */
public final class Tile {

    public enum Kind { APP, INPUT, ALL_APPS }

    public final String id;          // "app:<pkg>", "input:<inputId>", or "all_apps"
    public final String label;
    public final Kind kind;
    public final String pkg;         // APP only (for banner + brand colour); null otherwise
    public final Intent launch;      // null for ALL_APPS
    public final boolean connected;  // INPUT only

    private Tile(String id, String label, Kind kind, String pkg, Intent launch, boolean connected) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.pkg = pkg;
        this.launch = launch;
        this.connected = connected;
    }

    public static Tile app(String pkg, String label, Intent launch) {
        return new Tile("app:" + pkg, label, Kind.APP, pkg, launch, false);
    }

    public static Tile input(String inputId, String label, Intent launch, boolean connected) {
        return new Tile("input:" + inputId, label, Kind.INPUT, null, launch, connected);
    }

    public static Tile allApps() {
        return new Tile("all_apps", "All apps", Kind.ALL_APPS, null, null, false);
    }
}
