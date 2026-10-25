package com.simplehometv;

/**
 * How the dock lays out {@code n} tiles in {@code availDp} of width: one row while tiles stay a comfortable size,
 * then two rows, shrinking tiles to fit before falling back to scrolling. Pure, see {@code DockLayoutTest}.
 */
final class DockLayout {

    static final int AUTO = 0;
    static final int GAP = 12;
    static final int MAX_W = 148, MAX_W_TWO_ROWS = 116;
    static final int MIN_W_ONE_ROW = 104, MIN_W = 92;

    final int rows, cols, tileW, tileH;

    private DockLayout(int rows, int cols, int tileW) {
        this.rows = rows;
        this.cols = cols;
        this.tileW = tileW;
        this.tileH = Math.round(tileW * 9f / 16f);
    }

    /** {@code rowsPref} is {@link #AUTO}, 1 or 2. */
    static DockLayout compute(int n, int availDp, int rowsPref) {
        n = Math.max(1, n);
        int oneRow = fit(n, availDp, MAX_W);
        boolean two = rowsPref == 2 ? n > 1 : rowsPref == AUTO && oneRow < MIN_W_ONE_ROW;
        if (!two) return new DockLayout(1, n, Math.max(rowsPref == 1 ? MIN_W : MIN_W_ONE_ROW, Math.min(oneRow, MAX_W)));
        int cols = (n + 1) / 2;
        return new DockLayout(2, cols, Math.max(MIN_W, fit(cols, availDp, MAX_W_TWO_ROWS)));
    }

    /** True when the tiles are wider than the space and the dock has to scroll. */
    boolean overflows(int availDp) {
        return cols * tileW + (cols - 1) * GAP > availDp;
    }

    private static int fit(int cols, int availDp, int max) {
        return Math.min(max, (availDp - (cols - 1) * GAP) / cols);
    }
}
