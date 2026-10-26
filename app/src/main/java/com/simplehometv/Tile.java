package com.simplehometv;

import android.content.Intent;

/** One tile on the home: an app, a TV input (HDMI/AV), or the All apps button. */
public final class Tile {

    public enum Kind { APP, INPUT, ALL_APPS }

    public final String id;          // "app:<pkg>", "input:<inputId>" or "all_apps"
    public final String label;
    public final Kind kind;
    public final String pkg;         // APP only
    public final String inputId;     // INPUT only: the port, which the preview tunes
    public final String deviceId;    // INPUT only: what opening the input tunes (the port, or the CEC device on it)
    public final Intent launch;      // APP and INPUT only
    public final boolean connected;  // INPUT only: something is plugged in
    public final boolean on;         // INPUT only: and it is switched on, not in standby

    private Tile(String id, String label, Kind kind, String pkg, String inputId, String deviceId, Intent launch,
                 boolean connected, boolean on) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.pkg = pkg;
        this.inputId = inputId;
        this.deviceId = deviceId;
        this.launch = launch;
        this.connected = connected;
        this.on = on;
    }

    public static Tile app(String pkg, String label, Intent launch) {
        return new Tile("app:" + pkg, label, Kind.APP, pkg, null, null, launch, false, false);
    }

    public static Tile input(String portId, String deviceId, String label, Intent launch, boolean connected, boolean on) {
        return new Tile("input:" + portId, label, Kind.INPUT, null, portId, deviceId, launch, connected, on);
    }

    public static Tile allApps() {
        return new Tile("all_apps", "All apps", Kind.ALL_APPS, null, null, null, null, false, false);
    }
}
