package com.simplehometv;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/** Shared look: type, colours, shapes and motion. */
final class Ui {

    static final int TEXT = 0xFFF5F5F7;
    static final int TEXT_DIM = 0x99F5F5F7;
    static final int TEXT_FAINT = 0x8CF5F5F7;
    static final int CARD = 0xFF1F2228;
    static final int PANEL = 0xFF16181D;
    static final int ACCENT = 0xFF30D158;
    static final int RADIUS = 12;

    /** Material "emphasized" easing: quick start, long gentle settle. */
    static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Material "standard" easing: an even glide, for long moves where {@link #EASE} would whip through the middle. */
    static final Interpolator GLIDE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private static Typeface light, regular, medium, semibold;

    private Ui() { }

    static void init(Context c) {
        if (regular != null) return;
        light = font(c, "outfit_light");
        regular = font(c, "outfit_regular");
        medium = font(c, "outfit_medium");
        semibold = font(c, "outfit_semibold");
    }

    private static Typeface font(Context c, String name) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".ttf");
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    static Typeface light() { return light; }
    static Typeface regular() { return regular; }
    static Typeface medium() { return medium; }
    static Typeface semibold() { return semibold; }

    static TextView text(Context c, Typeface face, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static GradientDrawable stroke(int color, float width, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setStroke(Math.round(width), color);
        g.setCornerRadius(radius);
        return g;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static void clip(View v, float radius) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }
}
