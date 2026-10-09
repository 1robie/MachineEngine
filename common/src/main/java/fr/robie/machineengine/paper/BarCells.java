package fr.robie.machineengine.paper;

/**
 * Levels of the cells of a textured fill bar (docs/MENU_TEXTURES_PLAN.md): a bar over {@link #CELLS} slots
 * has {@link #LENGTH} pixels of fill; cell {@code k} (0 at the bottom or left) draws pixels
 * {@code [18k, min(LENGTH, 18k + 18))}: its slot and the 2 px gap after it (clients clip what an item
 * draws below or left of its slot, not above or right), so its level goes from 0 to 18 (16 for the last
 * cell). A menu shows cell {@code k} as the item {@code <shape>/<k>/<level>}.
 */
final class BarCells {
    static final int CELLS = 3;
    static final int LENGTH = 18 * CELLS - 2;

    private BarCells() {
    }

    /** Filled pixels: {@code value / total} of the bar, rounded down, but at least 1 when not empty. */
    static int filled(long value, long total) {
        if (value <= 0 || total <= 0) return 0;
        if (value >= total) return LENGTH;
        return Math.max(1, (int) ((double) value / total * LENGTH));
    }

    /** The level of {@code cell} (0 to 2), or -1 if there is no such cell. */
    static int level(long value, long total, int cell) {
        if (cell < 0 || cell >= CELLS) return -1;
        int start = 18 * cell;
        int end = Math.min(LENGTH, 18 * cell + 18);
        return Math.clamp(filled(value, total) - start, 0, end - start);
    }
}
