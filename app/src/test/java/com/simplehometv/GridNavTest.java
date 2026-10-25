package com.simplehometv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GridNavTest {

    // A content row of 5 above a two-row dock of 7 (4 + 3).
    private final GridNav nav = new GridNav(5, 5, 7, 4);

    @Test public void leftRightStayInsideARow() {
        assertEquals(1, nav.move(0, 1, 0));
        assertEquals(0, nav.move(0, -1, 0));
        assertEquals(4, nav.move(4, 1, 0));        // end of the content row
        assertEquals(9, nav.move(9, -1, 0));       // start of the dock's second row
        assertEquals(8, nav.move(8, 1, 0));        // end of the dock's first row
        assertEquals(11, nav.move(11, 1, 0));      // last tile
    }

    @Test public void downWalksRowsThenSections() {
        assertEquals(7, nav.move(2, 0, 1));        // content col 2 -> dock col 2
        assertEquals(8, nav.move(4, 0, 1));        // content col 4 clamps to dock col 3
        assertEquals(9, nav.move(5, 0, 1));        // dock row 1 -> row 2
        assertEquals(11, nav.move(8, 0, 1));       // short last row: falls to its last tile
        assertEquals(10, nav.move(10, 0, 1));      // bottom stays
    }

    @Test public void upWalksBackAndLeavesAtTheTop() {
        assertEquals(5, nav.move(9, 0, -1));
        assertEquals(1, nav.move(6, 0, -1));
        assertEquals(GridNav.OUT_TOP, nav.move(3, 0, -1));
    }

    @Test public void upFromDockLandsOnTheLastRowOfTheSectionAbove() {
        GridNav g = new GridNav(6, 4, 3, 3);       // 4 + 2 above, then 3
        assertEquals(5, g.move(6 + 1, 0, -1));     // col 1 -> second row col 1
        assertEquals(5, g.move(6 + 2, 0, -1));     // col 2 clamps to the short row's end
    }

    @Test public void emptySectionsAreSkipped() {
        GridNav g = new GridNav(0, 5, 3, 3);
        assertEquals(GridNav.OUT_TOP, g.move(1, 0, -1));
        assertEquals(1, g.start(1) + 1);
        assertEquals(1, g.section(0));
    }

    @Test public void outOfRangeIndexRecovers() {
        assertEquals(0, nav.move(99, 1, 0));
    }
}
