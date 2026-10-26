package com.simplehometv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the launchable apps and the TV inputs on this device. */
final class Catalog {

    /** Launcher entries that only open a background service's own settings, not an app. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList("com.google.android.tv.remote.service"));

    /** The TV's settings apps, reached from Settings instead of All apps: the vendor's own first. */
    static final List<String> SETTINGS = Arrays.asList("com.xiaomi.mitv.settings", "com.android.tv.settings", "com.android.settings");

    private Catalog() { }

    /** Every launchable app (leanback entry preferred), A to Z, minus this launcher itself. */
    static List<Tile> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Tile> byPkg = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || HIDDEN.contains(pkg) || SETTINGS.contains(pkg) || byPkg.containsKey(pkg)) continue;
                Intent launch = launchIntent(pm, pkg);
                if (launch == null) continue;
                byPkg.put(pkg, Tile.app(pkg, String.valueOf(ri.loadLabel(pm)).trim(), launch));
            }
        }
        List<Tile> out = new ArrayList<>(byPkg.values());
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** The vendor's settings app (Xiaomi's), when the TV has one with its own entry, else null. */
    static Tile vendorSettings(Context c) {
        PackageManager pm = c.getPackageManager();
        String pkg = SETTINGS.get(0);
        Intent launch = launchIntent(pm, pkg);
        if (launch == null) return null;
        try {
            return Tile.app(pkg, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))).trim(), launch);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    /** HDMI and AV inputs as tiles. Empty on a streamer (no hardware inputs). */
    static List<Tile> inputs(Context c) {
        List<Tile> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> hdmi = new ArrayList<>();
        List<TvInputInfo> av = new ArrayList<>();
        java.util.Map<String, TvInputInfo> plugged = new java.util.HashMap<>();   // port -> the CEC device on it
        try {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (i.getParentId() != null) {
                    plugged.put(i.getParentId(), i);
                    continue;
                }
                switch (i.getType()) {
                    case TvInputInfo.TYPE_HDMI:
                        hdmi.add(i);
                        break;
                    case TvInputInfo.TYPE_COMPOSITE:
                    case TvInputInfo.TYPE_COMPONENT:
                    case TvInputInfo.TYPE_SCART:
                    case TvInputInfo.TYPE_SVIDEO:
                    case TvInputInfo.TYPE_VGA:
                        av.add(i);
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            return out;
        }
        hdmi.sort((a, b) -> label(c, a).compareTo(label(c, b)));
        int n = 1;
        boolean viaDevice = !tvAppTakesPassthrough(c);
        for (TvInputInfo i : hdmi) {
            TvInputInfo device = plugged.get(i.getId());
            out.add(inputTile(c, tim, i, viaDevice && device != null ? device : i, named(c, i, device, "HDMI " + n++)));
        }
        n = 1;
        for (TvInputInfo i : av) out.add(inputTile(c, tim, i, i, av.size() == 1 ? "AV" : friendly(c, i, "AV " + n++)));
        return out;
    }

    private static Tile inputTile(Context c, TvInputManager tim, TvInputInfo port, TvInputInfo tune, String label) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tune.getId()), "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int state = state(tim, port.getId());
        return Tile.input(port.getId(), tune.getId(), label, view, state != TvInputManager.INPUT_STATE_DISCONNECTED,
                state == TvInputManager.INPUT_STATE_CONNECTED);
    }

    /**
     * Whether the TV has an app that opens inputs. Without one (Xiaomi), opening an input tunes the CEC device rather
     * than its port, which wakes it; the preview keeps to the port, since a woken device takes the TV over CEC.
     */
    static boolean tvAppTakesPassthrough(Context c) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput("x"), "vnd.android.cursor.item/channel");
        return c.getPackageManager().resolveActivity(view, 0) != null;
    }

    static int state(TvInputManager tim, String inputId) {
        try {
            return tim.getInputState(inputId);
        } catch (Exception e) {
            return TvInputManager.INPUT_STATE_DISCONNECTED;
        }
    }

    private static String label(Context c, TvInputInfo i) {
        CharSequence l = i.loadLabel(c);
        return l == null ? "" : l.toString();
    }

    /** The name given to the port in the TV's settings, else the device plugged into it, else a friendly name. */
    private static String named(Context c, TvInputInfo port, TvInputInfo device, String fallback) {
        CharSequence custom = port.loadCustomLabel(c);
        if (custom != null && custom.toString().trim().length() > 0) return custom.toString().trim();
        if (device != null) {
            String d = cecName(device);
            if (d.isEmpty()) {
                CharSequence l = device.loadCustomLabel(c);
                d = l != null && l.toString().trim().length() > 0 ? l.toString().trim() : label(c, device).trim();
            }
            // Without a name, CEC devices are labelled by type: "Playback_3".
            if (!d.isEmpty() && !d.contains("/") && !d.matches("(?i)(playback|recorder|tuner|audio ?system|tv|reserved)[_ ]?[0-9]*"))
                return d;
        }
        return friendly(c, port, fallback);
    }

    /** The name a CEC device gives itself ("SHIELD"), from a system API: empty where the TV doesn't allow it. */
    private static String cecName(TvInputInfo device) {
        try {
            Object info = TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(device);
            Object name = info == null ? null : info.getClass().getMethod("getDisplayName").invoke(info);
            return name == null ? "" : name.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Vendors sometimes label a port by its hardware id ("HW2"); show a friendly name instead. */
    private static String friendly(Context c, TvInputInfo i, String fallback) {
        String l = label(c, i).trim();
        return (l.isEmpty() || l.contains("HW") || l.contains("/")) ? fallback : l;
    }
}
