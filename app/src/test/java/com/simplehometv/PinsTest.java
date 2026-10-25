package com.simplehometv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public class PinsTest {

    private static final Function<String, String> ID = s -> s;

    @Test public void parsesAndSerializes() {
        Pins p = new Pins("a,b,c");
        assertEquals(Arrays.asList("a", "b", "c"), p.ids());
        assertEquals("a,b,c", p.serialize());
    }

    @Test public void emptyInput() {
        assertEquals(0, new Pins("").count());
        assertEquals(0, new Pins(null).count());
        assertEquals("", new Pins(null).serialize());
    }

    @Test public void dedupesAndTrimsOnParse() {
        assertEquals(Arrays.asList("a", "b"), new Pins(" a , b , a ").ids());
    }

    @Test public void togglePinsAndUnpins() {
        Pins p = new Pins("");
        p.toggle("x");
        assertTrue(p.isPinned("x"));
        p.toggle("x");
        assertFalse(p.isPinned("x"));
    }

    @Test public void pinAndUnpinAreIdempotent() {
        Pins p = new Pins("");
        p.pin("x");
        p.pin("x");
        assertEquals(1, p.count());
        p.unpin("x");
        p.unpin("x");
        assertEquals(0, p.count());
    }

    @Test public void orderPutsPinnedFirstInPinOrder() {
        Pins p = new Pins("c,a");
        List<String> items = Arrays.asList("a", "b", "c", "d");
        assertEquals(Arrays.asList("c", "a", "b", "d"), p.order(items, ID));
    }

    @Test public void orderKeepsUnpinnedOriginalOrder() {
        Pins p = new Pins("");
        List<String> items = Arrays.asList("d", "a", "c", "b");
        assertEquals(items, p.order(items, ID));
    }
}
