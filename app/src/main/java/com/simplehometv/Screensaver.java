package com.simplehometv;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Bitmap;
import android.view.animation.LinearInterpolator;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The TV's screensaver: aerial videos or a Google Photos album, with the time in a corner. */
public class Screensaver extends DreamService {

    private static final long PHOTO_MS = 12_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AerialBackground aerial;
    private ImageView photoA, photoB;
    private TextView clock, date;
    private final List<String> photos = new ArrayList<>();
    private int photoIndex;
    private volatile boolean running;

    private final Runnable tick = this::updateClock;
    private final Runnable nextPhoto = this::showNextPhoto;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Ui.init(this);
        Prefs prefs = new Prefs(this);
        float dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        aerial = new AerialBackground(this);
        root.addView(aerial, new FrameLayout.LayoutParams(-1, -1));
        photoA = photoView();
        photoB = photoView();
        root.addView(photoA, new FrameLayout.LayoutParams(-1, -1));
        root.addView(photoB, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.END);
        clock = Ui.text(this, Ui.light(), 56, Ui.TEXT);
        clock.setShadowLayer(12 * dp, 0, dp, 0x80000000);
        date = Ui.text(this, Ui.regular(), 17, 0xCCF5F5F7);
        date.setShadowLayer(10 * dp, 0, dp, 0x80000000);
        corner.addView(clock);
        corner.addView(date);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        cl.rightMargin = Math.round(64 * dp);
        cl.bottomMargin = Math.round(48 * dp);
        root.addView(corner, cl);
        corner.setVisibility(prefs.screensaverClock() ? View.VISIBLE : View.GONE);

        setContentView(root);
        running = true;
        updateClock();
        if (prefs.screensaverPhotos() && GooglePhotos.looksLikeAlbum(prefs.photosAlbum())) loadAlbum(prefs.photosAlbum());
    }

    @Override public void onDetachedFromWindow() {
        running = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(nextPhoto);
        if (aerial != null) aerial.stop();
        super.onDetachedFromWindow();
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    /** Photos replace the aerial video once the first one is ready; until then, or if the album fails, video plays. */
    private void loadAlbum(String url) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        new Thread(() -> {
            List<String> found = GooglePhotos.album(url, Math.max(m.widthPixels, 1920), Math.max(m.heightPixels, 1080));
            Collections.shuffle(found);
            main.post(() -> {
                if (!running || found.isEmpty()) return;
                photos.addAll(found);
                showNextPhoto();
            });
        }).start();
    }

    private void showNextPhoto() {
        if (!running || photos.isEmpty()) return;
        String url = photos.get(photoIndex++ % photos.size());
        new Thread(() -> {
            Bitmap b = ImageCache.load(this, url, 1920);
            main.post(() -> {
                if (!running) return;
                if (b != null) crossfade(b);
                main.postDelayed(nextPhoto, b != null ? PHOTO_MS : 1000);
            });
        }).start();
    }

    private void crossfade(Bitmap b) {
        ImageView in = photoA.getAlpha() > 0.5f ? photoB : photoA, out = in == photoA ? photoB : photoA;
        in.bringToFront();
        ((View) clock.getParent()).bringToFront();
        in.setImageBitmap(b);
        in.animate().alpha(1f).setDuration(1500).withEndAction(() -> {
            out.setAlpha(0f);
            if (aerial != null) aerial.stop();
        }).start();
        // A slow push-in keeps still photos from looking frozen.
        ObjectAnimator zoom = ObjectAnimator.ofPropertyValuesHolder(in,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
        zoom.setDuration(PHOTO_MS + 1500);
        zoom.setInterpolator(new LinearInterpolator());
        zoom.start();
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
        main.postDelayed(tick, 60_000 - (System.currentTimeMillis() % 60_000));
    }
}
