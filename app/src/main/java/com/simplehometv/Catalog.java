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
                if (pkg.equals(c.getPackageName()) || HIDDEN.contains(pkg) || byPkg.containsKey(pkg)) continue;
                Intent launch = launchIntent(pm, pkg);
                if (launch == null) continue;
                byPkg.put(pkg, Tile.app(pkg, String.valueOf(ri.loadLabel(pm)).trim(), launch));
            }
        }
        List<Tile> out = new ArrayList<>(byPkg.values());
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
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
        try {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (i.getParentId() != null) continue;
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
        for (TvInputInfo i : hdmi) out.add(inputTile(c, tim, i, friendly(c, i, "HDMI " + n++)));
        n = 1;
        for (TvInputInfo i : av) out.add(inputTile(c, tim, i, av.size() == 1 ? "AV" : friendly(c, i, "AV " + n++)));
        return out;
    }

    private static Tile inputTile(Context c, TvInputManager tim, TvInputInfo i, String label) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(i.getId()), "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int state = state(tim, i.getId());
        return Tile.input(i.getId(), label, view, state != TvInputManager.INPUT_STATE_DISCONNECTED,
                state == TvInputManager.INPUT_STATE_CONNECTED);
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

    /** Vendors sometimes label a port by its hardware id ("HW2"); show a friendly name instead. */
    private static String friendly(Context c, TvInputInfo i, String fallback) {
        String l = label(c, i).trim();
        return (l.isEmpty() || l.contains("HW") || l.contains("/")) ? fallback : l;
    }
}
