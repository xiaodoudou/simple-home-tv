package com.simplehometv;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Made-up content for checking the spotlight layout on a device with no TV provider (an emulator). On only when
 * a debug build finds files/demo: {@code adb shell run-as com.simplehometv touch files/demo}.
 */
final class Demo {

    private Demo() { }

    static boolean on(Context c) {
        return BuildConfig.DEBUG && new File(c.getFilesDir(), "demo").exists();
    }

    static List<Content.Source> sources(String pkg) {
        List<Content.Source> out = new ArrayList<>();
        if (pkg.hashCode() % 3 == 0) return out;
        out.add(source(Content.WATCH_NEXT, "Continue watching"));
        out.add(source("ch:Recommended", "Recommended"));
        return out;
    }

    static List<Content.Item> items(String pkg, List<Content.Source> sources, Set<String> hidden, int max) {
        List<Content.Item> out = new ArrayList<>();
        String[] titles = {"Slow Horses", "The Ark", "Grand Blue Dreaming", "Dragon Ball", "Paw Patrol", "Snatch",
                "Animals", "Hope", "The Addams Family", "Tuner"};
        for (Content.Source s : sources) {
            if (hidden.contains(s.key)) continue;
            for (int i = 0; i < 5 && out.size() < max; i++) {
                Content.Item it = new Content.Item();
                int n = (s.key.length() * 7 + i * 3 + pkg.length()) % titles.length;
                it.title = titles[n];
                it.sub = Content.WATCH_NEXT.equals(s.key) ? "Season " + (i + 1) + " · Episode " + (n + 2) : null;
                it.art = "https://picsum.photos/seed/" + pkg.hashCode() % 97 + "x" + n + "/1280/720";
                it.progress = Content.WATCH_NEXT.equals(s.key) ? 0.2f + 0.15f * i : 0f;
                out.add(it);
            }
        }
        return out;
    }

    private static Content.Source source(String key, String name) {
        Content.Source s = new Content.Source();
        s.key = key;
        s.name = name;
        return s;
    }
}
