package com.simplehometv;

/**
 * Tells a press of OK from a hold. A hold is the caller's timer firing, a key repeat well after the press began,
 * or the system's long-press flag; remotes differ in which of these they produce. The key-up that ends a hold,
 * and a key-up whose key-down went elsewhere, never select. Pure, see {@code OkKeyTest}.
 */
final class OkKey {

    static final long HOLD_MS = 550;
    static final long MIN_REPEAT_HOLD_MS = 300;   // early repeats come from remotes that repeat on their own

    private boolean down, held;
    private long downAt;

    /** A key-down. Returns true when this event makes the press a hold. */
    boolean onDown(int repeat, boolean longPress, long now) {
        if (repeat == 0 && !longPress) {
            down = true;
            held = false;
            downAt = now;
            return false;
        }
        if (!down || held) return false;
        if (longPress || now - downAt >= MIN_REPEAT_HOLD_MS) {
            held = true;
            return true;
        }
        return false;
    }

    /** The hold timer fired. Returns true when that makes the press a hold. */
    boolean onTimer() {
        if (!down || held) return false;
        held = true;
        return true;
    }

    /** A key-up. Returns true when it completes a short press that should select. */
    boolean onUp() {
        boolean select = down && !held;
        reset();
        return select;
    }

    boolean pressing() {
        return down && !held;
    }

    void reset() {
        down = false;
        held = false;
    }
}
