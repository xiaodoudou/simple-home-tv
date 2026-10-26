package com.simplehometv;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What an app publishes to the TV for the focus spotlight: its Watch Next entries and its preview channels.
 * Blocking provider queries: call off the UI thread.
 */
final class Content {

    private static final String TAG = "Content";
    static final String WATCH_NEXT = "watchnext";

    private Content() { }

    static final class Item {
        String title;
        String sub;     // "Season 3 · Episode 2", or null
        String art;     // poster/thumbnail URI (http(s) or content://)
        String intent;  // deep link into the app, or null
        float progress; // 0..1, 0 when unknown
    }

    /** One row an app publishes: its Watch Next entries, or one of its preview channels. */
    static final class Source {
        String key;     // WATCH_NEXT or "ch:<display name>"
        String name;
        long channelId = -1;
    }

    /** The app's sources in the order it publishes them, Watch Next first. */
    static List<Source> sources(Context c, String pkg) {
        List<Source> out = new ArrayList<>();
        if (pkg == null) return out;
        if (Demo.on(c)) return Demo.sources(pkg);
        if (!watchNext(c, pkg, 1).isEmpty()) {
            Source w = new Source();
            w.key = WATCH_NEXT;
            w.name = "Continue watching";
            out.add(w);
        }
        Map<String, Source> byName = new LinkedHashMap<>();
        String[] cols = {TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_DISPLAY_NAME};
        try (Cursor ch = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, cols, null, null, null)) {
            while (ch != null && ch.moveToNext()) {
                if (!pkg.equals(ch.getString(1)) || !TvContract.Channels.TYPE_PREVIEW.equals(ch.getString(2))) continue;
                String name = ch.getString(3);
                if (name == null || name.trim().isEmpty()) name = "Featured";
                // Some apps publish the same row twice: the first one wins.
                if (byName.containsKey(name) || previews(c, ch.getLong(0), 1).isEmpty()) continue;
                Source s = new Source();
                s.key = "ch:" + name;
                s.name = name;
                s.channelId = ch.getLong(0);
                byName.put(name, s);
            }
        } catch (Exception e) {
            Log.w(TAG, "channels: " + e);
        }
        out.addAll(byName.values());
        return out;
    }

    /** Up to {@code max} items from the sources not in {@code hidden}, deduplicated by title, in source order. */
    static List<Item> items(Context c, String pkg, List<Source> sources, Set<String> hidden, int max) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return new ArrayList<>();    // no Watch Next or preview programs before 8.0
        List<Item> out = new ArrayList<>();
        if (Demo.on(c)) return Demo.items(pkg, sources, hidden, max);
        Set<String> seen = new HashSet<>();
        for (Source s : sources) {
            if (hidden.contains(s.key)) continue;
            List<Item> part = WATCH_NEXT.equals(s.key) ? watchNext(c, pkg, max) : previews(c, s.channelId, max);
            for (Item it : part) {
                if (out.size() >= max) return out;
                if (seen.add(it.title.toLowerCase(Locale.ROOT))) out.add(it);
            }
        }
        Log.i(TAG, pkg + ": " + sources.size() + " sources, " + out.size() + " items");
        return out;
    }

    /** The app's "continue" and "next episode" entries, most recently watched first (how apps order them). */
    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private static List<Item> watchNext(Context c, String pkg, int max) {
        List<Item> out = new ArrayList<>();
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME, TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI, TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        };
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < max) {
                if (!pkg.equals(cur.getString(0))) continue;
                int type = cur.isNull(5) ? -1 : cur.getInt(5);
                if (type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        && type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) continue;
                if (!cur.isNull(9) && cur.getInt(9) == 0) continue;
                Item it = item(str(cur, 1), str(cur, 3), str(cur, 4), str(cur, 2), str(cur, 6), str(cur, 7), str(cur, 8));
                if (it == null) continue;
                long pos = cur.isNull(10) ? 0 : cur.getLong(10), dur = cur.isNull(11) ? 0 : cur.getLong(11);
                if (pos > 0 && dur > 0) it.progress = Math.min(1f, pos / (float) dur);
                out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "watch next: " + e);
        }
        return out;
    }

    /** A preview channel's programs in the order the app published them. */
    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private static List<Item> previews(Context c, long channelId, int max) {
        List<Item> out = new ArrayList<>();
        String[] cols = {
                TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
                TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI, TvContract.PreviewPrograms.COLUMN_BROWSABLE,
        };
        try (Cursor cur = c.getContentResolver().query(
                TvContract.buildPreviewProgramsUriForChannel(channelId), cols, null, null, null)) {
            while (cur != null && cur.moveToNext() && out.size() < max) {
                if (!cur.isNull(7) && cur.getInt(7) == 0) continue;
                Item it = item(str(cur, 0), str(cur, 1), str(cur, 2), str(cur, 3), str(cur, 4), str(cur, 5), str(cur, 6));
                if (it != null) out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "previews: " + e);
        }
        return out;
    }

    private static Item item(String title, String season, String episode, String epTitle,
                             String thumb, String poster, String intent) {
        if (title.isEmpty()) return null;
        Item it = new Item();
        it.title = title;
        it.sub = subtitle(season, episode, epTitle, title);
        it.art = !thumb.isEmpty() ? thumb : (poster.isEmpty() ? null : poster);
        it.intent = intent.isEmpty() ? null : intent;
        return it;
    }

    /** "Season 2 · Episode 5", or the episode title when there are no numbers. */
    static String subtitle(String season, String episode, String epTitle, String title) {
        StringBuilder m = new StringBuilder();
        if (season != null && !season.isEmpty()) m.append("Season ").append(season);
        if (episode != null && !episode.isEmpty()) m.append(m.length() > 0 ? " · " : "").append("Episode ").append(episode);
        if (m.length() == 0 && epTitle != null && !epTitle.isEmpty() && !epTitle.equals(title)) m.append(epTitle);
        return m.length() > 0 ? m.toString() : null;
    }

    static Uri parse(String intent) {
        try {
            return intent == null ? null : Uri.parse(intent);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Cursor c, int i) {
        String s = c.isNull(i) ? null : c.getString(i);
        return s == null ? "" : s.trim();
    }
}
