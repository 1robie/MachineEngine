package fr.robie.machineengine.paper.fluid;

import fr.robie.machineengine.paper.fluid.client.Cells;
import fr.robie.machineengine.paper.fluid.client.FluidCells;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Where a fluid may go (D46, docs/FLUID_TYPES_PLAN.md §4.4). The client colors a block from one 4×4×4
 * biome cell (its color area) and blends the areas around it:
 * <ul>
 *   <li><b>gap:</b> two different fluids inside one blend window can't be told apart, so different
 *   fluids keep {@code fluidGap} areas apart, whatever the conflict rule;</li>
 *   <li><b>conflict stop:</b> an area belongs to one kind of water, so a fluid stops where other water
 *   reads the area, and plain water stops where a fluid owns it.</li>
 * </ul>
 * The same rules decide buckets, spreading, {@link fr.robie.machineengine.api.paper.WorldFluids#canPlace}
 * and the placement grid.
 */
public final class FluidRules {
    /** The blocks of one world, as the rules need them. Read on the thread that owns the region. */
    public interface Blocks {
        /** Water or a waterlogged block. */
        boolean isWater(int x, int y, int z);

        /** Whether this column can be read from here (loaded, and owned by this thread on Folia). */
        boolean readable(int x, int z);
    }

    public enum Reason {
        /** Too many different fluid looks: this fluid got no key. */
        NO_KEY,
        /** Another fluid is closer than the gap; {@link Refusal#other()} names it. */
        OTHER_FLUID_NEAR,
        /** Plain water: a fluid owns that area; {@link Refusal#other()} names it. */
        AREA_OF_FLUID,
        /** A fluid: another fluid reads that area. */
        AREA_READ_BY_OTHER_FLUID,
        /** A fluid: plain water reads that area. */
        AREA_READ_BY_WATER
    }

    /** Why a fluid may not go somewhere. */
    public record Refusal(@NotNull Reason reason, int other) {
    }

    /** The settings in effect ({@code world-fluids.*}). */
    public record Settings(boolean conflictStop, int fluidGap, int padding, int dispersion) {
    }

    private FluidRules() {
    }

    /**
     * Why {@code fluid} (0 = plain water) may not be at the block, or {@code null}.
     */
    public static @Nullable Refusal check(@NotNull FluidCells cells, @NotNull UUID world, @NotNull Blocks blocks,
                                          int x, int y, int z, int fluid, @NotNull Settings settings) {
        if (fluid != 0 && !cells.keys().hasKey(fluid)) return new Refusal(Reason.NO_KEY, 0);
        long cell = cells.readCell(world, x, y, z);
        if (fluid != 0 && settings.fluidGap() > 0) {
            int other = cells.otherOwnerNear(world, cell, settings.fluidGap(), fluid);
            if (other != 0) return new Refusal(Reason.OTHER_FLUID_NEAR, other);
        }
        if (!settings.conflictStop()) return null;
        if (fluid == 0) {
            int owner = cells.owner(world, cell);
            return owner == 0 ? null : new Refusal(Reason.AREA_OF_FLUID, owner);
        }
        if (cells.readByOtherFluid(world, cell, fluid)) return new Refusal(Reason.AREA_READ_BY_OTHER_FLUID, 0);
        if (readersAreWater(cells, world, blocks, cell, x, y, z, true)) return new Refusal(Reason.AREA_READ_BY_WATER, 0);
        return null;
    }

    /**
     * Whether water reads a cell's color: plain water only ({@code plainOnly}), or any water. The block
     * at {@code exceptX/Y/Z} is not counted (the one being placed). Columns that can't be read count as
     * water, so nothing is placed or padded against what can't be seen.
     */
    public static boolean readersAreWater(@NotNull FluidCells cells, @NotNull UUID world, @NotNull Blocks blocks, long cell,
                                          int exceptX, int exceptY, int exceptZ, boolean plainOnly) {
        int cx = Cells.x(cell), cy = Cells.y(cell), cz = Cells.z(cell);
        // A block reads one of the 8 cells around (pos - 2) >> 2, so the readers of a cell lie in [4c - 2, 4c + 5].
        for (int x = 4 * cx - 2; x <= 4 * cx + 5; x++) {
            for (int z = 4 * cz - 2; z <= 4 * cz + 5; z++) {
                if (!blocks.readable(x, z)) return true;
                for (int y = 4 * cy - 2; y <= 4 * cy + 5; y++) {
                    if (x == exceptX && y == exceptY && z == exceptZ) continue;
                    if (!blocks.isWater(x, y, z)) continue;
                    if (plainOnly && cells.get(world, x, y, z) != 0) continue;
                    if (cells.readCell(world, x, y, z) == cell) return true;
                }
            }
        }
        return false;
    }

    /**
     * The key entry an unowned cell gets from the fluids around it, or 0:
     * <ul>
     *   <li><b>padding:</b> where no water reads the cell, the nearest fluid's full key (shores and air
     *   stay the fluid's color, cleaner corners);</li>
     *   <li><b>dispersion</b> (conflict allow): plain water beside a fluid shows it diluted with the
     *   neutral water, half in the first ring and a quarter in the second; sideways only.</li>
     * </ul>
     */
    public static int surroundingEntry(@NotNull FluidCells cells, @NotNull UUID world, @NotNull Blocks blocks, long cell,
                                       @NotNull Settings settings) {
        if (cells.owner(world, cell) != 0) return 0;
        if (!readersAreWater(cells, world, blocks, cell, Integer.MIN_VALUE, 0, 0, false)) {
            int[] near = cells.nearestOwner(world, cell, Math.min(settings.padding(), FluidKeys.MAX_RINGS), false);
            return near[0] == 0 ? 0 : cells.keys().entry(near[0], 0);
        }
        if (!settings.conflictStop() && settings.dispersion() > 0) {
            int[] near = cells.nearestOwner(world, cell, Math.min(settings.dispersion(), FluidKeys.MAX_RINGS), true);
            return near[0] == 0 ? 0 : cells.keys().entry(near[0], near[1]);
        }
        return 0;
    }
}
