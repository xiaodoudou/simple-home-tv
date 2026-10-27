package com.simplehometv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Apple's aerial clips (bundled list, assets/aerials.json) and which one plays next. */
final class Aerials {

    static final class Clip {
        String id, name, url, url4k;
        int mb, mb4k;
    }

    /** A first-run selection: varied, and on the small side so the first download is quick. */
    static final Set<String> DEFAULT = new HashSet<>(Arrays.asList(
            "AFA22C08-A486-4CE8-9A13-E355B6C38559",   // Liwa 1
            "3D729CFC-9000-48D3-A052-C5BD5B7A6842",   // Hawaii 1
            "E99FA658-A59A-4A2D-9F3B-58E7BDC71A9A",   // Hong Kong 1
            "44166C39-8566-4ECA-BD16-43159429B52F",   // New York 1
            "F604AF56-EA77-4960-AEF7-82533CC1A8B3",   // London 1
            "B8F204CE-6024-49AB-85F9-7CA2F6DCD226")); // Greenland 1

    private static List<Clip> all;

    private Aerials() { }

    static synchronized List<Clip> all(Context c) {
        if (all != null) return all;
        List<Clip> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("aerials.json")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Clip clip = new Clip();
                clip.id = o.getString("id");
                clip.name = o.getString("name");
                clip.url = o.getString("url");
                clip.mb = o.optInt("mb");
                clip.url4k = o.optString("url4k", clip.url);
                clip.mb4k = o.optInt("mb4k", clip.mb);
                out.add(clip);
            }
        } catch (Exception ignored) {
        }
        all = out;
        return out;
    }

    /** The chosen clips, in list order. */
    static List<Clip> chosen(Context c, Set<String> ids) {
        List<Clip> out = new ArrayList<>();
        for (Clip clip : all(c)) if (ids.contains(clip.id)) out.add(clip);
        // A selection made only of clips since dropped from the list (space, underwater) falls back to the default.
        if (out.isEmpty() && !ids.isEmpty()) for (Clip clip : all(c)) if (DEFAULT.contains(clip.id)) out.add(clip);
        return out;
    }

    /**
     * The id to play after {@code current}, among {@code ready} (in list order): the following one, wrapping round,
     * or a random other one when shuffling. Null when nothing is ready. Pure, see {@code AerialsTest}.
     */
    static String next(List<String> ready, String current, boolean shuffle, Random random) {
        if (ready.isEmpty()) return null;
        if (ready.size() == 1) return ready.get(0);
        if (shuffle) {
            String pick;
            do pick = ready.get(random.nextInt(ready.size())); while (pick.equals(current));
            return pick;
        }
        int at = ready.indexOf(current);
        return ready.get((at + 1) % ready.size());
    }
}
