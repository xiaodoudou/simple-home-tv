package com.simplehometv;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The TV's screensaver: aerial videos, with the time in a corner. */
public class Screensaver extends DreamService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private AerialBackground aerial;
    private TextView clock, date;

    private final Runnable tick = this::updateClock;

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
        updateClock();
    }

    @Override public void onDetachedFromWindow() {
        main.removeCallbacks(tick);
        if (aerial != null) aerial.stop();
        super.onDetachedFromWindow();
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
        main.postDelayed(tick, 60_000 - (System.currentTimeMillis() % 60_000));
    }
}
