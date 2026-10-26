package com.simplehometv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple's aerial videos behind the home and in the screensaver: the clips the user chose, one after another,
 * in list order or shuffled. Clips are downloaded once into the cache and played from there; the very first
 * one streams while it downloads. A dark gradient covers the video until a frame is ready.
 * A SurfaceView, not a TextureView: its frames go straight to the TV's video plane at the panel's full
 * resolution (4K), while the UI is drawn at 1080p.
 */
public class AerialBackground extends FrameLayout implements SurfaceHolder.Callback {

    private static final String TAG = "Aerial";
    private static final int KEEP = 3;                  // clips kept in the cache
    private static final long MIN_FREE = 2L << 30;      // leave the device at least 2 GB
    private static final int MIN_CLIP = 1 << 20;         // anything smaller is an error page, not a video

    // Shared: the home and the screensaver use the same cache, and TVs have few video decoders.
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static boolean downloading;
    private static AerialBackground playing;
    private static String lastId;                       // so the order carries on between home and screensaver

    private final SurfaceView video;
    private final View cover;
    private final android.widget.ImageView still;   // the frame the aerial was paused on, while an input shows
    // Dims the background while an input shows. The still is drawn with the UI, which the TV's picture processing
    // never touches, so it can't match the video's colours; the swap happens under the dim, where that can't show.
    private final View dim;
    static final float DIM = 0.55f;
    static final float BLUR = 18f;
    private final File dir;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private MediaPlayer mp;
    private SurfaceHolder holder;
    private boolean active = true;
    private boolean paused;             // stepped aside for an input's live picture
    private int retries;
    private String source;              // what was playing, to carry on from the same point
    private boolean sourceLocal;
    private int resumeAt;
    private Runnable onShown;
    private Runnable afterPause;
    private int revealAt;               // the paused position: the still stays until the video is back there

    public AerialBackground(Context c) {
        super(c);
        dir = new File(c.getCacheDir(), "aerial");
        prefs = new Prefs(c);
        video = new SurfaceView(c);
        video.getHolder().addCallback(this);
        addView(video, full());
        still = new android.widget.ImageView(c);
        still.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        still.setVisibility(GONE);
        addView(still, full());
        dim = new View(c);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        addView(dim, full());
        cover = new View(c);
        cover.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF141A20, 0xFF090B10, 0xFF05060A}));
        addView(cover, full());
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        if (!paused) playNext();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        release();
        holder = null;
    }

    public void stop() {
        cover.animate().cancel();
        cover.setAlpha(1f);
        release();
    }

    /** Resume after {@link #stop()} if the surface is still around. */
    public void resume() {
        if (holder != null && mp == null && !paused) playNext();
    }

    /**
     * Steps aside for an input's live picture, which needs the TV's video planes (an aerial plane is always drawn
     * over it). Remembers where the clip was.
     */
    public void pauseForInput(Runnable then) {
        if (paused) {
            if (then != null) then.run();
            return;
        }
        paused = true;
        afterPause = then;
        if (mp == null || holder == null || getWidth() == 0) {
            resumeAt = 0;
            leavePlane();
            return;
        }
        cover.animate().cancel();
        dim.animate().cancel();
        dim.animate().alpha(1f).setDuration(300).withEndAction(() -> {
            if (!paused || mp == null) return;
            // Pause first, so the frame copied below is the one on screen.
            try {
                mp.pause();
                resumeAt = mp.getCurrentPosition();
            } catch (Exception e) {
                resumeAt = 0;
            }
            afterNextFrame(this::copyStill);
        }).start();
    }

    /** Swaps the paused video for a picture of the same frame, drawn with the UI, then frees the plane. */
    private void copyStill() {
        if (!paused) return;
        android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(getWidth(), getHeight(),
                android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(video, frame, result -> {
                if (result != android.view.PixelCopy.SUCCESS) {
                    Log.w(TAG, "could not copy the aerial frame: " + result);
                    if (paused) leavePlane();
                    return;
                }
                still.animate().cancel();
                still.setImageBitmap(frame);
                still.setAlpha(1f);
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    still.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(BLUR, BLUR,
                            android.graphics.Shader.TileMode.CLAMP));
                }
                still.setVisibility(VISIBLE);
                afterNextFrame(() -> {
                    if (paused) leavePlane();
                });
                return;
            }, main);
        } catch (Exception e) {
            leavePlane();
        }
    }

    /**
     * Fades the still out once the resumed video is back at the paused frame. This TV's decoder resumes from the
     * keyframe before it, up to a few seconds earlier; fading at once would show the picture jump back.
     */
    private void revealWhenCaughtUp(MediaPlayer p, long since) {
        if (p != mp || still.getVisibility() != VISIBLE) return;
        int at;
        try {
            at = p.getCurrentPosition();
        } catch (Exception e) {
            at = Integer.MAX_VALUE;
        }
        if (at + 40 < revealAt && SystemClock.uptimeMillis() - since < 4000) {
            main.postDelayed(() -> revealWhenCaughtUp(p, since), 40);
            return;
        }
        still.animate().cancel();
        still.setVisibility(GONE);
        still.setImageBitmap(null);
        dimTo(0f, 600, null);
    }

    /** Fades the dim (black over the background) to {@code alpha}; {@code end} runs when it gets there. */
    public void dimTo(float alpha, long ms, Runnable end) {
        dim.animate().cancel();
        dim.animate().alpha(alpha).setStartDelay(0).setDuration(ms).withEndAction(end).start();
    }

    /** The paused frame, or null when there is none. */
    android.graphics.Bitmap stillFrame() {
        android.graphics.drawable.Drawable d = still.getVisibility() == VISIBLE ? still.getDrawable() : null;
        return d instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
    }

    /** Runs once the window has drawn the next frame, so what was just shown is really on screen. */
    private void afterNextFrame(Runnable r) {
        postOnAnimation(() -> postOnAnimation(() -> main.postDelayed(r, 34)));
    }

    private void leavePlane() {
        release();
        video.setVisibility(GONE);      // frees the plane
        Runnable then = afterPause;
        afterPause = null;
        if (then != null) main.postDelayed(then, 250);
    }

    /** Carries on from where {@link #pauseForInput()} left off; {@code onShown} runs at its first frame. */
    public void resumeAfterInput(Runnable onShown) {
        if (onShown != null) this.onShown = onShown;
        if (!paused) {
            if (mp != null && this.onShown != null && cover.getAlpha() == 0f) {
                Runnable r = this.onShown;
                this.onShown = null;
                r.run();
            }
            return;
        }
        paused = false;
        if (mp != null) {
            // Left before the pause finished: the player is still here, so carry on with it.
            afterPause = null;
            resumeAt = 0;
            try {
                mp.start();
            } catch (Exception ignored) {
            }
            dim.animate().cancel();
            dim.animate().alpha(0f).setDuration(300).start();
            still.animate().cancel();
            still.setVisibility(GONE);
            if (this.onShown != null) {
                Runnable r = this.onShown;
                this.onShown = null;
                r.run();
            }
            return;
        }
        if (video.getVisibility() != VISIBLE) video.setVisibility(VISIBLE);   // surfaceCreated plays
        else if (holder != null) playNext();
    }

    /** Off leaves only the gradient. */
    public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) resume();
        else stop();
    }

    /** Whether to use the 4K files: the user's choice, else whether the screen is 4K. */
    static boolean use4k(Context c) {
        Boolean chosen = new Prefs(c).aerial4k();
        if (chosen != null) return chosen;
        try {
            Display d = ((android.hardware.display.DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE))
                    .getDisplay(Display.DEFAULT_DISPLAY);
            for (Display.Mode m : d.getSupportedModes()) if (m.getPhysicalWidth() >= 3840) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void playNext() {
        if (!active || paused || holder == null) return;
        if (source != null && resumeAt > 0) {
            play(source, sourceLocal);  // carry on with the clip that stepped aside
            return;
        }
        boolean uhd = use4k(getContext());
        List<Aerials.Clip> chosen = Aerials.chosen(getContext(), prefs.aerialIds());
        if (chosen.isEmpty()) return;
        List<String> ready = new ArrayList<>();
        for (Aerials.Clip clip : chosen) if (fileFor(clip, uhd).length() >= MIN_CLIP) ready.add(clip.id);
        String id = Aerials.next(ready, lastId, prefs.aerialShuffle(), random);
        if (id != null) {
            lastId = id;
            play(fileFor(byId(chosen, id), uhd).getPath(), true);
        } else {
            // Streams the Full HD file even for 4K: the media server ignores this app's trust of Apple's root.
            Aerials.Clip first = chosen.get(prefs.aerialShuffle() ? random.nextInt(chosen.size()) : 0);
            lastId = first.id;
            play(first.url, false);
        }
        fetchMore(chosen, uhd, ready.size());
    }

    private void play(String source, boolean local) {
        if (holder == null) return;
        release();
        this.source = source;
        sourceLocal = local;
        if (playing != null && playing != this) playing.release();
        playing = this;
        try {
            mp = new MediaPlayer();
            mp.setDisplay(holder);
            mp.setVolume(0f, 0f);
            mp.setDataSource(source);
            mp.setOnPreparedListener(p -> {
                retries = 0;
                revealAt = resumeAt;
                if (resumeAt > 0) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(resumeAt, MediaPlayer.SEEK_CLOSEST);
                    else p.seekTo(resumeAt);
                }
                resumeAt = 0;
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    cover.animate().alpha(0f).setDuration(900).start();
                    if (still.getVisibility() == VISIBLE) revealWhenCaughtUp(p, SystemClock.uptimeMillis());
                    if (onShown != null) {
                        Runnable r = onShown;
                        onShown = null;
                        r.run();
                    }
                }
                return false;
            });
            mp.setOnCompletionListener(p -> cover.animate().alpha(1f).setDuration(500).withEndAction(this::playNext).start());
            mp.setOnErrorListener((p, w, e) -> {
                Log.w(TAG, "can't play " + source + " (" + w + "/" + e + ")");
                release();
                if (paused) return true;        // an input took the decoder; the still frame stays
                cover.setAlpha(1f);
                boolean broken = e == MediaPlayer.MEDIA_ERROR_MALFORMED || e == MediaPlayer.MEDIA_ERROR_UNSUPPORTED;
                if (local && broken) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(source).delete();
                } else if (retries++ < 3) {
                    // Usually the decoder is still held by a player that was just released: try again shortly.
                    main.postDelayed(() -> { if (mp == null && active) play(source, local); }, 1500);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "play failed: " + e);
            release();
        }
    }

    /** Downloads chosen clips that are not cached yet, up to {@link #KEEP}, starting after the one playing. */
    private void fetchMore(List<Aerials.Clip> chosen, boolean uhd, int ready) {
        if (downloading || ready >= Math.min(KEEP, chosen.size())) {
            if (!downloading) trim(chosen, uhd);
            return;
        }
        downloading = true;
        int start = Math.max(0, indexOf(chosen, lastId));
        io.execute(() -> {
            try {
                dir.mkdirs();
                trim(chosen, uhd);
                int have = ready;
                for (int k = 0; k < chosen.size() && have < KEEP; k++) {
                    Aerials.Clip clip = chosen.get((start + 1 + k) % chosen.size());
                    File f = fileFor(clip, uhd);
                    if (f.length() >= MIN_CLIP) continue;
                    if (dir.getUsableSpace() < MIN_FREE) break;
                    if (download(uhd ? clip.url4k : clip.url, f)) have++;
                }
            } finally {
                main.post(() -> downloading = false);
            }
        });
    }

    private boolean download(String url, File to) {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection con = null;
        try {
            Log.i(TAG, "downloading " + to.getName());
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(30_000);
            if (con.getResponseCode() != 200 || con.getContentLength() < MIN_CLIP) {
                throw new java.io.IOException("HTTP " + con.getResponseCode() + ", " + con.getContentLength() + " bytes");
            }
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            boolean ok = part.length() >= MIN_CLIP && part.renameTo(to);
            Log.i(TAG, "downloaded " + to.getName() + " " + (to.length() / (1024 * 1024)) + " MB");
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + e);
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Deletes cached clips that are no longer chosen, or of the other quality, and stray partial downloads. */
    private void trim(List<Aerials.Clip> chosen, boolean uhd) {
        Set<String> keep = new HashSet<>();
        for (Aerials.Clip clip : chosen) keep.add(fileFor(clip, uhd).getName());
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            boolean partial = f.getName().endsWith(".part");
            boolean stale = !partial && (!keep.contains(f.getName()) || f.length() < MIN_CLIP);
            if (stale || (partial && !downloading)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private File fileFor(Aerials.Clip clip, boolean uhd) {
        return new File(dir, clip.id + (uhd ? "-4k" : "-hd") + ".mov");
    }

    private static Aerials.Clip byId(List<Aerials.Clip> list, String id) {
        return list.get(Math.max(0, indexOf(list, id)));
    }

    private static int indexOf(List<Aerials.Clip> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }

    private void release() {
        if (playing == this) playing = null;

        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
    }

    private static LayoutParams full() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
