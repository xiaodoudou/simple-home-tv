package com.simplehometv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OkKeyTest {

    @Test public void shortPressSelects() {
        OkKey k = new OkKey();
        assertFalse(k.onDown(0, false, 0));
        assertTrue(k.onUp());
    }

    @Test public void timerMakesAHoldAndTheReleaseDoesNotSelect() {
        OkKey k = new OkKey();
        k.onDown(0, false, 0);
        assertTrue(k.onTimer());
        assertFalse(k.onTimer());
        assertFalse(k.onUp());
    }

    @Test public void lateRepeatIsAHold() {
        OkKey k = new OkKey();
        k.onDown(0, false, 0);
        assertTrue(k.onDown(1, false, 500));
        assertFalse(k.onDown(2, false, 550));
        assertFalse(k.onUp());
    }

    @Test public void earlyRepeatIsNotAHold() {
        OkKey k = new OkKey();
        k.onDown(0, false, 0);
        assertFalse(k.onDown(1, false, 60));
        assertTrue(k.onUp());
    }

    @Test public void longPressFlagIsAHoldAtOnce() {
        OkKey k = new OkKey();
        k.onDown(0, false, 0);
        assertTrue(k.onDown(1, true, 5));
        assertFalse(k.onUp());
    }

    @Test public void strayReleaseDoesNotSelect() {
        OkKey k = new OkKey();
        assertFalse(k.onUp());
        assertFalse(k.onTimer());
    }

    @Test public void resetDropsAPressInProgress() {
        OkKey k = new OkKey();
        k.onDown(0, false, 0);
        k.reset();
        assertFalse(k.onUp());
    }

    @Test public void nextPressAfterAHoldSelects() {
        OkKey k = new OkKey();
        k.onDown(0, false, 0);
        k.onTimer();
        k.onUp();
        k.onDown(0, false, 2000);
        assertTrue(k.onUp());
    }
}
