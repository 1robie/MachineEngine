package fr.robie.machineengine.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarCellsTest {
    @Test
    void emptyAndFull() {
        for (int cell = 0; cell < BarCells.CELLS; cell++) {
            assertEquals(0, BarCells.level(0, 1000, cell));
        }
        assertEquals(18, BarCells.level(1000, 1000, 0));
        assertEquals(18, BarCells.level(1000, 1000, 1));
        assertEquals(16, BarCells.level(1000, 1000, 2));
        assertEquals(16, BarCells.level(5000, 1000, 2), "more than the capacity is full");
    }

    @Test
    void anyAmountShowsAPixel() {
        assertEquals(1, BarCells.filled(1, 1_000_000));
        assertEquals(1, BarCells.level(1, 1_000_000, 0));
        assertEquals(0, BarCells.level(1, 1_000_000, 1));
    }

    @Test
    void nearlyFullIsNotFull() {
        assertEquals(BarCells.LENGTH - 1, BarCells.filled(999, 1000));
        assertEquals(15, BarCells.level(999, 1000, 2));
    }

    @Test
    void cellsShareThePixels() {
        // Half of 52 px: the bottom cell is full (18), the middle one has the other 8.
        assertEquals(18, BarCells.level(50, 100, 0));
        assertEquals(8, BarCells.level(50, 100, 1));
        assertEquals(0, BarCells.level(50, 100, 2));
        for (long amount = 0; amount <= 100; amount++) {
            int sum = 0;
            for (int cell = 0; cell < BarCells.CELLS; cell++) sum += BarCells.level(amount, 100, cell);
            assertEquals(BarCells.filled(amount, 100), sum, "levels add up to the filled pixels at " + amount);
        }
    }

    @Test
    void hugeAmountsDoNotOverflow() {
        assertEquals(BarCells.LENGTH / 2, BarCells.filled(Long.MAX_VALUE / 2, Long.MAX_VALUE));
    }

    @Test
    void badCellsAndCapacities() {
        assertEquals(-1, BarCells.level(5, 10, 3));
        assertEquals(-1, BarCells.level(5, 10, -1));
        assertEquals(0, BarCells.level(5, 0, 0));
    }
}
