package com.simplehometv;

/**
 * D-pad movement over stacked grids (for example a content row above a two-row dock). Items are numbered across
 * all sections in order; each section fills its rows left to right. Pure, see {@code GridNavTest}.
 */
final class GridNav {

    /** Returned when moving up from the top row of the first section. */
    static final int OUT_TOP = -1;

    private final int[] counts, cols;

    /** {@code sections} is pairs of (item count, columns). */
    GridNav(int... sections) {
        counts = new int[sections.length / 2];
        cols = new int[sections.length / 2];
        for (int s = 0; s < counts.length; s++) {
            counts[s] = Math.max(0, sections[2 * s]);
            cols[s] = Math.max(1, sections[2 * s + 1]);
        }
    }

    int size() {
        int n = 0;
        for (int c : counts) n += c;
        return n;
    }

    /** The section an index is in, or -1 if out of range. */
    int section(int i) {
        if (i < 0) return -1;
        for (int s = 0; s < counts.length; s++) {
            if (i < counts[s]) return s;
            i -= counts[s];
        }
        return -1;
    }

    int start(int section) {
        int n = 0;
        for (int s = 0; s < section; s++) n += counts[s];
        return n;
    }

    int move(int i, int dx, int dy) {
        int s = section(i);
        if (s < 0) return size() > 0 ? 0 : i;
        int j = i - start(s), c = cols[s], col = j % c, row = j / c;
        if (dx != 0) {
            int nc = col + dx;
            return nc >= 0 && nc < c && j + dx < counts[s] ? i + dx : i;
        }
        if (dy > 0) {
            if (j + c < counts[s]) return i + c;
            if (row < (counts[s] - 1) / c) return start(s) + counts[s] - 1;
            for (int t = s + 1; t < counts.length; t++) {
                if (counts[t] > 0) return start(t) + Math.min(Math.min(col, cols[t] - 1), counts[t] - 1);
            }
            return i;
        }
        if (dy < 0) {
            if (row > 0) return i - c;
            for (int t = s - 1; t >= 0; t--) {
                if (counts[t] == 0) continue;
                int lastRow = (counts[t] - 1) / cols[t] * cols[t];
                return start(t) + Math.min(lastRow + Math.min(col, cols[t] - 1), counts[t] - 1);
            }
            return OUT_TOP;
        }
        return i;
    }
}
