package com.simplehometv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DockLayoutTest {

    private static final int W = 832;   // 960 dp screen minus the overscan gutters

    @Test public void fewTilesUseOneRowAtMaxSize() {
        DockLayout l = DockLayout.compute(4, W, DockLayout.AUTO);
        assertEquals(1, l.rows);
        assertEquals(4, l.cols);
        assertEquals(DockLayout.MAX_W, l.tileW);
        assertEquals(Math.round(l.tileW * 9f / 16f), l.tileH);
    }

    @Test public void tilesShrinkBeforeWrapping() {
        DockLayout l = DockLayout.compute(7, W, DockLayout.AUTO);
        assertEquals(1, l.rows);
        assertTrue(l.tileW < DockLayout.MAX_W);
        assertTrue(l.tileW >= DockLayout.MIN_W_ONE_ROW);
        assertFalse(l.overflows(W));
    }

    @Test public void manyTilesWrapToTwoRows() {
        DockLayout l = DockLayout.compute(12, W, DockLayout.AUTO);
        assertEquals(2, l.rows);
        assertEquals(6, l.cols);
        assertFalse(l.overflows(W));
    }

    @Test public void oddCountPutsTheExtraTileOnTheFirstRow() {
        assertEquals(5, DockLayout.compute(9, W, DockLayout.AUTO).cols);
    }

    @Test public void hugeDockScrolls() {
        DockLayout l = DockLayout.compute(30, W, DockLayout.AUTO);
        assertEquals(2, l.rows);
        assertEquals(DockLayout.MIN_W, l.tileW);
        assertTrue(l.overflows(W));
    }

    @Test public void forcedOneRowNeverWraps() {
        DockLayout l = DockLayout.compute(12, W, 1);
        assertEquals(1, l.rows);
        assertEquals(12, l.cols);
    }

    @Test public void forcedTwoRowsWrapsEvenWhenFew() {
        DockLayout l = DockLayout.compute(4, W, 2);
        assertEquals(2, l.rows);
        assertEquals(2, l.cols);
        assertEquals(1, DockLayout.compute(1, W, 2).rows);
    }
}
