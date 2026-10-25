package com.simplehometv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A looping, muted aerial video behind the home. The first run streams a clip while another downloads into
 * the cache; later runs play cached clips locally. A dark gradient shows until a frame is ready, and stays if
 * nothing plays. Drawn on a TextureView so it composites under the UI like any other view.
 */
public class AerialBackground extends FrameLayout implements TextureView.SurfaceTextureListener {

    private static final String TAG = "Aerial";
    private static final int KEEP = 2;   // clips kept in the cache

    // Plain http on purpose: sylvan's certificate chains to an Apple root that Android TV trust stores lack.
    static final String[] CLIPS = {
            "http://sylvan.apple.com/Videos/comp_A105_C003_0212CT_FLARE_v10_SDR_PS_FINAL_20180711_SDR_2K_AVC.mov",
            "http://sylvan.apple.com/Videos/comp_GMT026_363A_103NC_E1027_KOREA_JAPAN_NIGHT_v18_SDR_PS_20180907_SDR_2K_AVC.mov",
            "http://sylvan.apple.com/Videos/SE_A016_C009_SDR_20190717_SDR_2K_AVC.mov",
            "http://sylvan.apple.com/Videos/comp_GL_G002_C002_PSNK_v03_SDR_PS_20180925_SDR_2K_AVC.mov",
            "http://sylvan.apple.com/Videos/PA_A001_C007_SDR_20190717_SDR_2K_AVC.mov"
    };
    private static final long MIN_FREE = 2L << 30;   // leave the device at least 2 GB

    private final TextureView video;
    private final View fallback;
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private Surface surface;
    private boolean downloading;
    private boolean active = true;

    public AerialBackground(Context c) {
        super(c);
        dir = new File(c.getCacheDir(), "aerial");
        fallback = new View(c);
        fallback.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF141A20, 0xFF090B10, 0xFF05060A}));
        addView(fallback, full());
        video = new TextureView(c);
        video.setOpaque(true);
        video.setAlpha(0f);
        addView(video, full());
        video.setSurfaceTextureListener(this);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        playCachedOrFetch();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    public void stop() {
        video.animate().cancel();
        video.setAlpha(0f);
        release();
    }

    /** Resume after {@link #stop()} if the surface is still around. */
    public void resume() {
        if (surface != null && mp == null) playCachedOrFetch();
    }

    /** Off leaves only the gradient. */
    public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) resume();
        else stop();
    }

    private void playCachedOrFetch() {
        if (!active) return;
        List<File> cached = cachedClips();
        if (!cached.isEmpty()) {
            play(cached.get((int) (Math.random() * cached.size())).getPath(), true);
            if (cached.size() < KEEP) fetchNext(-1);
        } else {
            int streamed = (int) (System.currentTimeMillis() / 60_000 % CLIPS.length);
            play(CLIPS[streamed], false);
            fetchNext(streamed);
        }
    }

    private void play(String source, boolean local) {
        if (surface == null) return;
        release();
        try {
            mp = new MediaPlayer();
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            mp.setDataSource(source);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    video.animate().alpha(1f).setDuration(800).start();
                }
                return false;
            });
            mp.setOnErrorListener((p, w, e) -> {
                Log.w(TAG, "can't play " + source + " (" + w + "/" + e + ")");
                release();
                video.setAlpha(0f);
                //noinspection ResultOfMethodCallIgnored
                if (local) new File(source).delete();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "play failed: " + e);
            release();
        }
    }

    /** Downloads one clip not yet cached, other than {@code skip}; plays it if nothing else is playing. */
    private void fetchNext(int skip) {
        if (downloading) return;
        downloading = true;
        io.execute(() -> {
            File done = null;
            try {
                dir.mkdirs();
                for (int idx = 0; idx < CLIPS.length && done == null; idx++) {
                    File f = fileFor(idx);
                    if (idx == skip || f.exists()) continue;
                    if (dir.getUsableSpace() < MIN_FREE) break;
                    if (download(CLIPS[idx], f)) done = f;
                }
                trim();
            } catch (Exception e) {
                Log.w(TAG, "fetch failed: " + e);
            }
            File landed = done;
            main.post(() -> {
                downloading = false;
                if (landed != null && active && mp == null) play(landed.getPath(), true);
            });
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
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            boolean ok = part.renameTo(to);
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

    private List<File> cachedClips() {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".mov") && f.length() > 0) out.add(f);
        return out;
    }

    /** Keeps at most {@link #KEEP} clips (oldest go first). */
    private void trim() {
        List<File> cached = cachedClips();
        cached.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        while (cached.size() > KEEP) {
            //noinspection ResultOfMethodCallIgnored
            cached.remove(0).delete();
        }
    }

    private File fileFor(int idx) {
        return new File(dir, "clip" + idx + ".mov");
    }

    private void release() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
    }

    private LayoutParams full() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
