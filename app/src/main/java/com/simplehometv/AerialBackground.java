package com.simplehometv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
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
    // While an input preview is open the TV's video planes belong to it (an aerial plane would cover it), so the
    // player draws through this TextureView instead: composited with the UI, Full HD, but still moving.
    private final TextureView soft;
    private Surface softSurface;
    private boolean softMode;
    private boolean leavingPlane;       // the SurfaceView is being hidden on purpose: keep the player
    private final View cover;
    private final File dir;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private MediaPlayer mp;
    private SurfaceHolder holder;
    private boolean active = true;
    private int retries;
    private String source;              // what is playing, to carry on from the same point on another surface
    private boolean sourceLocal;
    private int resumeAt;

    public AerialBackground(Context c) {
        super(c);
        dir = new File(c.getCacheDir(), "aerial");
        prefs = new Prefs(c);
        video = new SurfaceView(c);
        video.getHolder().addCallback(this);
        addView(video, full());
        soft = new TextureView(c);
        soft.setOpaque(true);
        soft.setAlpha(0f);
        soft.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                softSurface = new Surface(st);
                if (softMode) toSoft();
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (softSurface != null) softSurface.release();
                softSurface = null;
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (softMode && soft.getAlpha() == 0f) soft.setAlpha(1f);
            }
        });
        addView(soft, full());
        cover = new View(c);
        cover.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF141A20, 0xFF090B10, 0xFF05060A}));
        addView(cover, full());
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        leavingPlane = false;
        if (mp != null && !softMode) {
            moveTo();
            return;
        }
        if (mp == null) playNext();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        if (!leavingPlane) release();
        holder = null;
    }

    public void stop() {
        cover.animate().cancel();
        cover.setAlpha(1f);
        release();
    }

    /** Resume after {@link #stop()} if the surface is still around. */
    public void resume() {
        if (mp == null && (holder != null || (softMode && softSurface != null))) playNext();
    }

    /** Hands the video planes to an input preview: the aerial keeps playing, drawn with the UI. */
    public void freeze() {
        if (softMode) return;
        softMode = true;
        if (softSurface != null) toSoft();
    }

    /** Takes the 4K video plane back. */
    public void unfreeze() {
        if (!softMode) return;
        softMode = false;
        video.setVisibility(VISIBLE);   // surfaceCreated moves the player back onto it
    }

    private void toSoft() {
        leavingPlane = true;
        video.setVisibility(GONE);      // frees the plane
        if (mp != null) moveTo();
        else playNext();
    }

    /**
     * Restarts the current clip, from where it was, on the surface for the current mode. Handing a running player
     * a new surface leaves this TV's decoder stalled on a black frame.
     */
    private void moveTo() {
        int at = 0;
        try {
            at = mp.getCurrentPosition();
        } catch (Exception ignored) {
        }
        String src = source;
        boolean local = sourceLocal;
        release();
        if (src == null) {
            playNext();
            return;
        }
        resumeAt = at;
        play(src, local);
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
        if (!active || (softMode ? softSurface == null : holder == null)) return;
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
        if (softMode ? softSurface == null : holder == null) return;
        release();
        this.source = source;
        sourceLocal = local;
        if (playing != null && playing != this) playing.release();
        playing = this;
        try {
            mp = new MediaPlayer();
            if (softMode) mp.setSurface(softSurface);
            else mp.setDisplay(holder);
            mp.setVolume(0f, 0f);
            mp.setDataSource(source);
            mp.setOnPreparedListener(p -> {
                retries = 0;
                if (resumeAt > 0) p.seekTo(resumeAt);
                resumeAt = 0;
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    cover.animate().alpha(0f).setDuration(900).start();
                    if (!softMode) soft.setAlpha(0f);
                }
                return false;
            });
            mp.setOnCompletionListener(p -> cover.animate().alpha(1f).setDuration(500).withEndAction(this::playNext).start());
            mp.setOnErrorListener((p, w, e) -> {
                Log.w(TAG, "can't play " + source + " (" + w + "/" + e + ")");
                release();
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
