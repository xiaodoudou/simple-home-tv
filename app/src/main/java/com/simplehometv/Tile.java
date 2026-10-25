package com.simplehometv;

import android.content.Intent;

/** One tile on the home: an app, a TV input (HDMI/AV), the All apps button, or the Get apps shortcut. */
public final class Tile {

    public enum Kind { APP, INPUT, ALL_APPS, GET_APPS }

    public final String id;          // "app:<pkg>", "input:<inputId>", "all_apps" or "get_apps"
    public final String label;
    public final Kind kind;
    public final String pkg;         // APP only
    public final String inputId;     // INPUT only
    public final Intent launch;      // APP and INPUT only
    public final boolean connected;  // INPUT only

    private Tile(String id, String label, Kind kind, String pkg, String inputId, Intent launch, boolean connected) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.pkg = pkg;
        this.inputId = inputId;
        this.launch = launch;
        this.connected = connected;
    }

    public static Tile app(String pkg, String label, Intent launch) {
        return new Tile("app:" + pkg, label, Kind.APP, pkg, null, launch, false);
    }

    public static Tile input(String inputId, String label, Intent launch, boolean connected) {
        return new Tile("input:" + inputId, label, Kind.INPUT, null, inputId, launch, connected);
    }

    public static Tile allApps() {
        return new Tile("all_apps", "All apps", Kind.ALL_APPS, null, null, null, false);
    }

    public static Tile getApps() {
        return new Tile("get_apps", "Get apps", Kind.GET_APPS, null, null, null, false);
    }
}
