package com.simplehometv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class AerialsTest {

    private final List<String> ready = Arrays.asList("a", "b", "c");

    @Test public void inOrderGoesToTheNextAndWraps() {
        assertEquals("b", Aerials.next(ready, "a", false, new Random(1)));
        assertEquals("a", Aerials.next(ready, "c", false, new Random(1)));
    }

    @Test public void inOrderStartsAtTheTopWhenNothingPlayed() {
        assertEquals("a", Aerials.next(ready, null, false, new Random(1)));
        assertEquals("a", Aerials.next(ready, "gone", false, new Random(1)));
    }

    @Test public void shuffleNeverRepeatsTheCurrentClip() {
        Random r = new Random(7);
        for (int i = 0; i < 50; i++) assertNotEquals("b", Aerials.next(ready, "b", true, r));
    }

    @Test public void singleClipRepeats() {
        assertEquals("a", Aerials.next(Collections.singletonList("a"), "a", true, new Random(1)));
    }

    @Test public void nothingReady() {
        assertNull(Aerials.next(Collections.emptyList(), "a", false, new Random(1)));
    }
}
