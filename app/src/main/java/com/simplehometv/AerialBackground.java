package com.simplehometv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.graphics.SurfaceTexture;
import android.view.Display;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
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
 * resolution (4K), while the UI is drawn at 1080p. Some TVs (Xiaomi) only show that plane while the window
 * animates, so there the video is drawn in the window instead, through a TextureView.
 */
public class AerialBackground extends FrameLayout {

    private static final String TAG = "Aerial";
    private static final int KEEP = 3;                  // clips kept in the cache
    private static final long MIN_FREE = 2L << 30;      // leave the device at least 2 GB
    private static final int MIN_CLIP = 1 << 20;         // anything smaller is an error page, not a video

    // Shared: the home and the screensaver use the same cache, and TVs have few video decoders.
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static boolean downloading;
    private static AerialBackground playing;
    private static String lastId;                       // so the order carries on between home and screensaver

    private final View video;           // a SurfaceView, or a TextureView when inWindow
    final boolean inWindow;
    private final View cover;
    private final android.widget.ImageView still;   // the frame the aerial was paused on, while an input shows
    private final android.widget.ImageView soft;    // a blurred copy of it, faded in over it
    // Dims the background while an input shows. The still is drawn with the UI, which the TV's picture processing
    // never touches, so it can't match the video's colours; the swap happens under the dim, where that can't show.
    private final View dim;
    static final float DIM = 0.55f;
    private final File dir;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private MediaPlayer mp;
    private Surface surface;
    private boolean active = true;
    private boolean paused;             // stepped aside for an input's live picture
    private int retries;
    private String source;              // what was playing, to carry on from the same point
    private boolean sourceLocal;
    private int resumeAt;
    private int revealAt;               // the frozen position: restoring plays up to here

    public AerialBackground(Context c) {
        super(c);
        dir = new File(c.getCacheDir(), "aerial");
        prefs = new Prefs(c);
        inWindow = inWindow(c);
        if (inWindow) {
            TextureView t = new TextureView(c);
            t.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    surfaceReady(new Surface(st));
                }

                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    Surface s = surface;
                    surfaceGone();
                    if (s != null) s.release();
                    return true;
                }

                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
            });
            video = t;
        } else {
            SurfaceView s = new SurfaceView(c);
            s.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    surfaceReady(h.getSurface());
                }

                @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) { }

                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    surfaceGone();
                }
            });
            video = s;
        }
        addView(video, full());
        still = new android.widget.ImageView(c);
        still.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        still.setVisibility(GONE);
        addView(still, full());
        soft = new android.widget.ImageView(c);
        soft.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        soft.setAlpha(0f);
        soft.setVisibility(GONE);
        addView(soft, full());
        dim = new View(c);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(0f);
        addView(dim, full());
        cover = new View(c);
        cover.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF141A20, 0xFF090B10, 0xFF05060A}));
        addView(cover, full());
    }

    private void surfaceReady(Surface s) {
        surface = s;
        if (!paused && !held) playNext();
    }

    private void surfaceGone() {
        release();
        surface = null;
    }

    /** Draw the video in the window: the user's choice, else on Xiaomi TVs, whose video plane stays hidden. */
    static boolean inWindow(Context c) {
        Boolean chosen = new Prefs(c).aerialInWindow();
        return chosen != null ? chosen : "xiaomi".equalsIgnoreCase(android.os.Build.MANUFACTURER);
    }

    /** Copies the video's current frame into {@code frame}, then calls back with a PixelCopy result. */
    private void copyFrame(android.graphics.Bitmap frame, java.util.function.IntConsumer result) {
        if (video instanceof TextureView) {
            ((TextureView) video).getBitmap(frame);
            result.accept(PixelCopy.SUCCESS);
        } else {
            PixelCopy.request((SurfaceView) video, frame, result::accept, main);
        }
    }

    public void stop() {
        cover.animate().cancel();
        cover.setAlpha(1f);
        release();
    }

    /** Resume after {@link #stop()} if the surface is still around. */
    public void resume() {
        if (surface != null && mp == null && playing != this && !paused && !held) playNext();
    }

    // ---- stepping aside for an input's live picture ----
    //
    // The input's picture and the aerial both need the TV's video planes, and the TV always draws the aerial's plane
    // over the input's. So for an input the aerial is frozen on a copy of its current frame (drawn with the UI),
    // the copy is blurred, and the video plane is freed; coming back runs the same steps in reverse. Each step is a
    // separate call so the home can fit its own (the input box) between them.

    private boolean frozen;             // the still is up, over the paused (or released) video
    private Runnable whenCaughtUp;

    /**
     * Pauses the video, crossfades a copy of the same frame over it (so the change from the TV's processed picture
     * to the plain copy is gradual), then blurs and dims the copy. {@code done} runs once fully blurred; the
     * paused video is still on its plane until {@link #releasePlane()}.
     */
    public void freeze(Runnable done) {
        if (frozen) {
            animateBlur(1f, BLUR_MS, done);
            return;
        }
        frozen = true;
        paused = true;
        if (mp == null || surface == null || getWidth() == 0) {
            resumeAt = 0;
            cover.animate().cancel();
            cover.animate().alpha(1f).setDuration(STILL_MS).withEndAction(done).start();   // the gradient stands in
            return;
        }
        try {
            mp.pause();
            resumeAt = mp.getCurrentPosition();
        } catch (Exception e) {
            resumeAt = 0;
        }
        afterNextFrame(() -> copyStill(done));
    }

    private void copyStill(Runnable done) {
        if (!frozen) return;
        android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(getWidth(), getHeight(),
                android.graphics.Bitmap.Config.ARGB_8888);
        try {
            copyFrame(frame, result -> {
                if (!frozen) return;
                if (result != PixelCopy.SUCCESS) {
                    Log.w(TAG, "could not copy the aerial frame: " + result);
                    cover.animate().cancel();
                    cover.animate().alpha(1f).setDuration(STILL_MS).withEndAction(done).start();
                    return;
                }
                still.animate().cancel();
                still.setImageBitmap(frame);
                soft.setImageBitmap(Blur.of(frame));
                setBlur(0f);
                still.setAlpha(0f);
                still.setVisibility(VISIBLE);
                soft.setVisibility(VISIBLE);
                still.animate().alpha(1f).setDuration(STILL_MS).withEndAction(() -> {
                    if (frozen) animateBlur(1f, BLUR_MS, done);
                }).start();
            });
        } catch (Exception e) {
            if (done != null) done.run();
        }
    }

    // ---- the fast path, when the input's session is already open ----
    //
    // The player is never released: it stays paused on its frame, and its surface shrinks to one pixel so the
    // input's picture shows; coming back is just growing it again, on the exact same 4K frame. Only the blur is a
    // copy, faded over and off the real paused video.

    private boolean planeHidden;

    /** Pauses the video and fades a blurred, dimmed copy of the frame over it; {@code done} runs once covered. */
    public void blurOver(Runnable done) {
        if (frozen) {
            animateBlur(1f, BLUR_MS, done);
            return;
        }
        frozen = true;
        paused = true;
        if (mp == null || surface == null || getWidth() == 0) {
            cover.animate().cancel();
            cover.animate().alpha(1f).setDuration(STILL_MS).withEndAction(done).start();
            return;
        }
        try {
            mp.pause();
            resumeAt = mp.getCurrentPosition();
        } catch (Exception e) {
            resumeAt = 0;
        }
        afterNextFrame(() -> {
            if (!frozen) return;
            android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(getWidth(), getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            try {
                copyFrame(frame, result -> {
                    if (!frozen) return;
                    if (result != PixelCopy.SUCCESS) {
                        cover.animate().cancel();
                        cover.animate().alpha(1f).setDuration(STILL_MS).withEndAction(done).start();
                        return;
                    }
                    soft.setImageBitmap(Blur.of(frame));
                    setBlur(0f);
                    soft.setVisibility(VISIBLE);
                    animateBlur(1f, BLUR_MS, done);
                });
            } catch (Exception e) {
                if (done != null) done.run();
            }
        });
    }

    /** Shrinks the paused video to one pixel, freeing the screen for the input's picture. */
    public void hidePlane() {
        if (!frozen || mp == null || inWindow) return;
        planeHidden = true;
        video.setLayoutParams(new LayoutParams(1, 1));
    }

    public boolean isPlaneHidden() {
        return planeHidden;
    }

    /** Whether the sharp copy is up (the slow path, where the player was released). */
    public boolean isStillUp() {
        return still.getVisibility() == VISIBLE;
    }

    /** Grows the paused video back (same frame, so instantly), then fades the blur off it and plays on. */
    public void unblurOff(Runnable shown) {
        if (!frozen) {
            if (shown != null) shown.run();
            return;
        }
        if (planeHidden) {
            planeHidden = false;
            video.setLayoutParams(full());
        }
        afterNextFrame(() -> {
            if (!frozen) return;
            if (shown != null) shown.run();
            animateBlur(0f, BLUR_MS, () -> {
                if (!frozen || still.getVisibility() == VISIBLE) return;
                frozen = false;
                paused = false;
                soft.setVisibility(GONE);
                soft.setImageBitmap(null);
                if (mp != null) {
                    try {
                        mp.start();
                    } catch (Exception ignored) {
                    }
                    cover.animate().cancel();
                    cover.animate().alpha(0f).setDuration(STILL_MS).start();
                } else {
                    resume();
                }
            });
        });
    }

    /** Frees the video plane under the frozen frame, keeping where the clip was. */
    public void releasePlane() {
        if (!frozen) return;
        release();
        if (!inWindow) video.setVisibility(GONE);   // a TextureView keeps its surface while hidden: restore replays it
    }

    /**
     * Puts the clip back on its plane under the frozen frame and lets it play up to the frozen moment (this TV
     * resumes from the keyframe before it), then pauses it there: {@code done} runs with the real video showing
     * the same frame as the copy over it.
     */
    public void restore(Runnable done) {
        if (!frozen || mp != null) {    // never released: it is paused on the frozen frame already
            if (done != null) done.run();
            return;
        }
        whenCaughtUp = done;
        paused = false;
        holdPaused = true;
        if (video.getVisibility() != VISIBLE) video.setVisibility(VISIBLE);   // surfaceCreated plays
        else if (surface != null) playNext();
    }

    /** Unblurs and undims back to the sharp frozen frame. */
    public void unblur(Runnable done) {
        animateBlur(0f, BLUR_MS, done);
    }

    /** Crossfades from the frozen copy to the real video paused under it, then lets the video play on. */
    public void thaw() {
        if (!frozen) return;
        frozen = false;
        paused = false;
        holdPaused = false;
        whenCaughtUp = null;
        if (blurAnim != null) blurAnim.cancel();
        setBlur(0f);
        if (mp != null) {
            try {
                mp.start();
            } catch (Exception ignored) {
            }
            cover.animate().cancel();
            cover.animate().alpha(0f).setDuration(STILL_MS).start();
        } else {
            resume();
        }
        still.animate().cancel();
        still.animate().alpha(0f).setDuration(STILL_MS).withEndAction(() -> {
            still.setVisibility(GONE);
            still.setImageBitmap(null);
            soft.setVisibility(GONE);
            soft.setImageBitmap(null);
        }).start();
    }

    public boolean isFrozen() {
        return frozen;
    }

    static final long STILL_MS = 250;
    private boolean holdPaused;         // restoring: stop at the frozen moment instead of playing on

    private void waitCaughtUp(MediaPlayer p, long since) {
        if (p != mp || !holdPaused) return;
        int at;
        try {
            at = p.getCurrentPosition();
        } catch (Exception e) {
            at = Integer.MAX_VALUE;
        }
        if (at + 20 < revealAt && SystemClock.uptimeMillis() - since < 5000) {
            main.postDelayed(() -> waitCaughtUp(p, since), 15);
            return;
        }
        try {
            p.pause();
        } catch (Exception ignored) {
        }
        Runnable done = whenCaughtUp;
        whenCaughtUp = null;
        if (done != null) afterNextFrame(done);     // a frame for the paused picture to reach the screen
    }

    static final long BLUR_MS = 420;
    private android.animation.ValueAnimator blurAnim;
    private float blurLevel;

    /** Blur and dim together, 0 (the sharp frame) to 1 (blurred and dimmed behind an input). */
    private void animateBlur(float to, long ms, Runnable end) {
        if (blurAnim != null) blurAnim.cancel();
        blurAnim = android.animation.ValueAnimator.ofFloat(blurLevel, to);
        blurAnim.setDuration(ms);
        blurAnim.setInterpolator(Ui.EASE);
        blurAnim.addUpdateListener(a -> setBlur((float) a.getAnimatedValue()));
        if (end != null) {
            blurAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) end.run(); }
            });
        }
        blurAnim.start();
    }

    private void setBlur(float level) {
        blurLevel = level;
        soft.setAlpha(level);
        dim.setAlpha(DIM * level);
    }

    /** The blurred copy of the frozen frame (smaller than the screen), or null when there is none. */
    android.graphics.Bitmap softFrame() {
        android.graphics.drawable.Drawable d = soft.getVisibility() == VISIBLE ? soft.getDrawable() : null;
        return d instanceof android.graphics.drawable.BitmapDrawable ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
    }

    /** Runs once the window has drawn the next frame, so what was just shown is really on screen. */
    private void afterNextFrame(Runnable r) {
        postOnAnimation(() -> postOnAnimation(() -> main.postDelayed(r, 34)));
    }

    /** Holds playback back until released: the home opens an input first, before the aerial takes a plane. */
    public void setHeld(boolean held) {
        if (this.held == held) return;
        this.held = held;
        if (!held) resume();
    }

    private boolean held;

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
        // In the window the video is drawn at the UI's 1080p, and on these TVs a 4K decode starves the inputs.
        if (inWindow(c)) return false;
        try {
            Display d = ((android.hardware.display.DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE))
                    .getDisplay(Display.DEFAULT_DISPLAY);
            for (Display.Mode m : d.getSupportedModes()) if (m.getPhysicalWidth() >= 3840) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private void playNext() {
        if (!active || paused || held || surface == null) return;
        if (source != null && (resumeAt > 0 || frozen)) {
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
        if (surface == null) return;
        release();
        this.source = source;
        sourceLocal = local;
        if (playing != null && playing != this) playing.release();
        playing = this;
        int gen = playGen;
        // The surface and the decoder stay taken until the releases queued above have run.
        releaser.execute(() -> main.post(() -> {
            if (gen == playGen && playing == this && surface != null) open(source, local);
        }));
    }

    private void open(String source, boolean local) {
        try {
            mp = new MediaPlayer();
            if (video instanceof SurfaceView) mp.setDisplay(((SurfaceView) video).getHolder());
            else mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setDataSource(source);
            // A released player is let go off the main thread, so its callbacks can still arrive: ignore them.
            mp.setOnPreparedListener(p -> {
                if (p != mp) return;
                retries = 0;
                revealAt = resumeAt;
                if (resumeAt > 0) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) p.seekTo(resumeAt, MediaPlayer.SEEK_CLOSEST);
                    else p.seekTo(resumeAt);
                }
                resumeAt = 0;
                p.start();
            });
            loading(true);
            mp.setOnInfoListener((p, what, extra) -> {
                if (p == mp && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    loading(!local);            // a streamed clip is still downloading
                    if (holdPaused) waitCaughtUp(p, SystemClock.uptimeMillis());
                    else cover.animate().alpha(0f).setDuration(900).start();
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                if (p == mp) cover.animate().alpha(1f).setDuration(500).withEndAction(this::playNext).start();
            });
            mp.setOnErrorListener((p, w, e) -> {
                if (p != mp) return true;
                Log.w(TAG, "can't play " + source + " (" + w + "/" + e + ")");
                release();
                if (frozen) return true;        // an input took the decoder; the frozen frame stays
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
        playGen++;
        if (playing == this) playing = null;
        loading(false);
        if (mp != null) {
            MediaPlayer old = mp;
            mp = null;
            // Some media servers (Xiaomi's) take seconds to answer a release; on the main thread that is an ANR,
            // and the firmware then force-stops the app, which also switches off the Home button service.
            releaser.execute(() -> {
                try { old.release(); } catch (Exception ignored) { }
            });
        }
    }

    private static final ExecutorService releaser = Executors.newSingleThreadExecutor();
    private int playGen;

    private java.util.function.Consumer<Boolean> onLoading;
    private boolean isLoading;

    /** Told when a clip starts loading (or streaming while it downloads) and when that is over. */
    void setOnLoading(java.util.function.Consumer<Boolean> l) {
        onLoading = l;
    }

    private void loading(boolean on) {
        if (on == isLoading) return;
        isLoading = on;
        if (onLoading != null) onLoading.accept(on);
    }

    private static LayoutParams full() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
