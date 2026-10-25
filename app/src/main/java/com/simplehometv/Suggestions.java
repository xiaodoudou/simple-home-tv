package com.simplehometv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Apps worth having on a TV, picked by whether the device has Google Play. Without Play the official YouTube app
 * does not run, so SmartTube stands in, and Aurora Store (an anonymous Play client) is how the rest get installed.
 */
final class Suggestions {

    static final String PLAY = "com.android.vending";
    static final String AURORA = "com.aurora.store";

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

    /** The store that will open market:// links, or null when there is none. */
    static String store(Context c) {
        if (hasPlay(c)) return PLAY;
        if (installed(c, AURORA)) return AURORA;
        return null;
    }

    static List<App> list(Context c) {
        List<App> out = new ArrayList<>();
        if (hasPlay(c)) {
            out.add(new App("com.google.android.youtube.tv", "YouTube", "Official app", null));
            out.add(new App("com.netflix.ninja", "Netflix", "Official app", null));
            out.add(new App("com.amazon.amazonvideo.livingroom", "Prime Video", "Official app", null));
        } else {
            out.add(new App(AURORA, "Aurora Store", "Installs apps from Google Play without Google services", "f-droid.org/packages/com.aurora.store"));
            out.add(new App("com.teamsmart.videomanager.tv", "SmartTube", "YouTube without ads, no Google services needed", "smarttubeapp.github.io"));
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

    /** Opens the store page for {@code pkg} in the given store. */
    static Intent storeIntent(String store, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg))
                .setPackage(store)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
