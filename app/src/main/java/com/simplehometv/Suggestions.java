package com.simplehometv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Apps worth having on a TV, picked by whether the device has Google Play. Without Play the official YouTube app
 * does not run, so SmartTube stands in, and APKUpdater (a Google Play client with a TV layout) is how the rest get
 * installed.
 */
final class Suggestions {

    static final String PLAY = "com.android.vending";
    static final String APKUPDATER = "com.apkupdater.ci";

    /** The stores Settings can pick from, by package, with their names, in the order tried when none is picked. */
    private static final String[][] STORES = {
            {PLAY, "Google Play"}, {APKUPDATER, "APKUpdater"}, {"com.apkupdater", "APKUpdater"},
            {"com.aurora.store", "Aurora Store"}};

    static final class App {
        final String pkg, name, why, site;   // site: where to get it when no store carries it

        App(String pkg, String name, String why, String site) {
            this.pkg = pkg;
            this.name = name;
            this.why = why;
            this.site = site;
        }
    }

    private Suggestions() { }

    static boolean hasPlay(Context c) {
        return installed(c, PLAY);
    }

    /** The installed stores, as packages. */
    static List<String> stores(Context c) {
        List<String> out = new ArrayList<>();
        for (String[] s : STORES) if (installed(c, s[0])) out.add(s[0]);
        return out;
    }

    /** The store {@code picked} in Settings when it is still installed, else the first installed one, else null. */
    static String store(Context c, String picked) {
        List<String> all = stores(c);
        if (picked != null && all.contains(picked)) return picked;
        return all.isEmpty() ? null : all.get(0);
    }

    static String storeName(String pkg) {
        for (String[] s : STORES) if (s[0].equals(pkg)) return s[1];
        return pkg;
    }

    static List<App> list(Context c) {
        List<App> out = new ArrayList<>();
        if (hasPlay(c)) {
            out.add(new App("com.google.android.youtube.tv", "YouTube", "Official app", null));
            out.add(new App("com.netflix.ninja", "Netflix", "Official app", null));
            out.add(new App("com.amazon.amazonvideo.livingroom", "Prime Video", "Official app", null));
        } else {
            out.add(new App(APKUPDATER, "APKUpdater", "Installs and updates Google Play apps with the remote, no Google services", "github.com/rumboalla/apkupdater"));
            out.add(new App("org.smarttube.stable", "SmartTube", "YouTube without ads, no Google services needed", "smarttubeapp.github.io"));
        }
        out.add(new App("com.plexapp.android", "Plex", "Your media server, movies and shows", null));
        out.add(new App("org.jellyfin.androidtv", "Jellyfin", "Free media server client", null));
        out.add(new App("org.xbmc.kodi", "Kodi", "Plays almost anything, local or network", null));
        out.add(new App("org.videolan.vlc", "VLC", "Simple player for files and streams", null));
        return out;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The store's page for {@code pkg} when the store opens market:// links, else null. */
    static Intent pageIntent(Context c, String store, String pkg) {
        Intent page = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))
                .setPackage(store)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return c.getPackageManager().queryIntentActivities(page, 0).isEmpty() ? null : page;
    }
}
