package com.simplehometv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gets an app straight from where its makers publish it, for TVs without a store: F-Droid ("fdroid:<package>") or a
 * GitHub project's latest release ("github:<owner>/<repo>"), then opens the APK in Android's installer, which asks
 * the user to confirm. Through ACTION_VIEW rather than a PackageInstaller session: Xiaomi's firmware has no screen
 * for a session's confirmation.
 */
final class Installer {

    private static final String TAG = "Installer";
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, String> state = new HashMap<>();   // package -> what is happening, for Settings
    private static Runnable onChange;

    private Installer() { }

    /** What is happening to {@code pkg}, or null when nothing is. */
    static synchronized String state(String pkg) {
        return state.get(pkg);
    }

    /** Called on the main thread whenever a state changes; one listener (the Settings screen) at a time. */
    static void onChange(Runnable r) {
        onChange = r;
    }

    static void install(Context c, String pkg, String source) {
        Context app = c.getApplicationContext();
        if (state(pkg) != null && !state(pkg).startsWith("Failed")) return;
        if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
            set(pkg, "Allow installing apps, then try again");
            try {
                c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + app.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // Some TVs have no such screen and allow it globally instead.
            }
            return;
        }
        set(pkg, "Looking for it");
        io.execute(() -> {
            File[] old = dir(app).listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File apk = new File(dir(app), pkg + ".apk");
            try {
                String url = resolve(source);
                Log.i(TAG, pkg + " from " + url);
                download(url, apk, pkg);
                main.post(() -> {
                    try {
                        c.startActivity(new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(ApkProvider.uri(apk), "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                        set(pkg, null);
                    } catch (Exception e) {
                        Log.w(TAG, pkg + ": no installer: " + e);
                        set(pkg, "Failed, select to retry");
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, pkg + ": " + e);
                set(pkg, "Failed, select to retry");
            }
        });
    }

    /** Where downloads wait for the installer; emptied at the next install. */
    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "install");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static synchronized void set(String pkg, String s) {
        if (s == null) state.remove(pkg);
        else state.put(pkg, s);
        main.post(() -> {
            if (onChange != null) onChange.run();
        });
    }

    // ---- where the APK is ----

    static String resolve(String source) throws Exception {
        if (source.startsWith("fdroid:")) {
            String pkg = source.substring(7);
            Matcher m = Pattern.compile("\"suggestedVersionCode\"\\s*:\\s*(\\d+)")
                    .matcher(text("https://f-droid.org/api/v1/packages/" + pkg));
            if (!m.find()) throw new Exception("no version on F-Droid");
            return "https://f-droid.org/repo/" + pkg + "_" + m.group(1) + ".apk";
        }
        // The release pages, not the API: the API allows 60 calls an hour per address, shared by every TV at home.
        String repo = source.substring(7);
        String tag = redirect("https://github.com/" + repo + "/releases/latest").replaceFirst(".*/tag/", "");
        Matcher m = Pattern.compile("href=\"(/" + Pattern.quote(repo) + "/releases/download/[^\"]+\\.apk)\"")
                .matcher(text("https://github.com/" + repo + "/releases/expanded_assets/" + tag));
        List<String> apks = new ArrayList<>();
        while (m.find()) apks.add(m.group(1));
        String pick = pick(apks, Build.SUPPORTED_ABIS);
        if (pick == null) throw new Exception("no APK in " + repo + " " + tag);
        return "https://github.com" + pick;
    }

    /** The build for this TV's processor, else a universal one, never a root variant. */
    static String pick(List<String> apks, String[] abis) {
        List<String> ok = new ArrayList<>();
        for (String a : apks) {
            String name = a.substring(a.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (!name.matches(".*[-_]root[-_.].*")) ok.add(a);
        }
        for (String abi : abis) for (String a : ok) if (a.toLowerCase(Locale.ROOT).contains(abi.toLowerCase(Locale.ROOT))) return a;
        for (String a : ok) if (a.toLowerCase(Locale.ROOT).contains("universal")) return a;
        for (String a : ok) if (!a.matches("(?i).*(arm64|armeabi|x86).*")) return a;
        return ok.isEmpty() ? null : ok.get(0);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "SimpleHomeTV");
        return c;
    }

    private static String text(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        } finally {
            c.disconnect();
        }
    }

    private static String redirect(String url) throws Exception {
        HttpURLConnection c = open(url);
        c.setInstanceFollowRedirects(false);
        try {
            String to = c.getHeaderField("Location");
            if (to == null) throw new Exception("no release at " + url);
            return to;
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to, String pkg) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            long total = c.getContentLengthLong(), got = 0;
            int last = -1;
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (pct != last && pct % 5 == 0) {
                    last = pct;
                    set(pkg, "Downloading " + pct + "%");
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
