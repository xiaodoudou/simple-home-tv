package com.simplehometv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Downloads and disk-caches artwork (http(s) or content://) and decodes it near a target width. Call off the UI thread. */
final class ImageCache {

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private ImageCache() { }

    // A missing image is asked for again on every focus otherwise: one request per card per visit.
    private static final java.util.Map<String, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RETRY_MS = 10 * 60_000;

    static Bitmap peek(String uri, int maxW) {
        return uri == null ? null : MEM.get(maxW + "|" + uri);
    }

    static Bitmap peek(String uri, String alt, int maxW) {
        Bitmap b = peek(uri, maxW);
        return b != null ? b : peek(alt, maxW);
    }

    /** {@code uri}, or {@code alt} when that can't be had. */
    static Bitmap load(Context c, String uri, String alt, int maxW) {
        Bitmap b = load(c, uri, maxW);
        return b != null || alt == null ? b : load(c, alt, maxW);
    }

    static Bitmap load(Context c, String uri, int maxW) {
        if (uri == null || uri.isEmpty()) return null;
        String key = maxW + "|" + uri;
        Bitmap hit = MEM.get(key);
        if (hit != null) return hit;
        Long failed = FAILED.get(uri);
        if (failed != null && android.os.SystemClock.uptimeMillis() - failed < RETRY_MS) return null;
        try {
            File f = cacheFile(c, uri);
            if (!f.exists() || f.length() == 0) fetch(c, uri, f);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
            int sampled = o.outWidth / sample;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            if (sampled > maxW) {
                // Sampling only halves, which can leave twice the width needed: the decoder scales the rest.
                o.inScaled = true;
                o.inDensity = sampled;
                o.inTargetDensity = maxW;
            }
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) MEM.put(key, b);
            else FAILED.put(uri, android.os.SystemClock.uptimeMillis());
            return b;
        } catch (Exception e) {
            FAILED.put(uri, android.os.SystemClock.uptimeMillis());
            return null;
        }
    }


    private static File cacheFile(Context c, String uri) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] h = md.digest(uri.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        File dir = new File(c.getCacheDir(), "art");
        dir.mkdirs();
        return new File(dir, sb + ".img");
    }

    private static void fetch(Context c, String uri, File to) throws IOException {
        File tmp = new File(to.getPath() + ".part");
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(12000);
            con.setInstanceFollowRedirects(true);
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                copy(in, out);
            } finally {
                con.disconnect();
            }
        } else {
            try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri));
                 OutputStream out = new FileOutputStream(tmp)) {
                copy(in, out);
            }
        }
        if (!tmp.renameTo(to)) throw new IOException("cannot save " + to.getName());
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null) throw new IOException("no stream");
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
