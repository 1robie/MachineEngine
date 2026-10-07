package fr.robie.machineengine.api.multiblock;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What blocks a multiblock is made of, and where (D33). Two kinds:
 * <ul>
 *   <li>{@link Box}: a hollow box of any size within limits (Mekanism's tanks, boilers, reactors);</li>
 *   <li>{@link Pattern}: a fixed shape given as layers of rows, matched in four horizontal rotations.</li>
 * </ul>
 */
public sealed interface MultiblockShape permits MultiblockShape.Box, MultiblockShape.Pattern {

    /** Every block that can be part of the structure itself (not its interior). */
    @NotNull Set<Key> blocks();

    /**
     * A hollow box: frame blocks make its six walls (an open top: five), casings every edge and corner,
     * and its interior holds what {@code interior} allows. The anchor is its lowest corner.
     *
     * @param casing     the block of its edges and corners (and of its anchor)
     * @param faces      blocks allowed in its walls (the casing, valves, ports, glass...)
     * @param interior   blocks allowed inside (e.g. only air for a tank)
     * @param minSize    the smallest outer size on each axis (at least 3)
     * @param maxSize    the largest outer size on each axis
     * @param width      the outer width and depth it must have, or 0 for any
     * @param openTop    no roof: the top layer is part of the interior, walls go round it
     * @param controller a block that must be in the walls exactly once, or {@code null}
     */
    record Box(@NotNull Key casing, @NotNull Set<Key> faces, @NotNull Predicate<Key> interior, int minSize, int maxSize,
               int width, boolean openTop, @Nullable Key controller) implements MultiblockShape {
        public static final int MIN_SIZE = 3;
        public static final int MAX_SIZE = 18;

        public Box {
            Objects.requireNonNull(casing);
            faces = Set.copyOf(faces);
            Objects.requireNonNull(interior);
            if (minSize < MIN_SIZE || maxSize < minSize) throw new IllegalArgumentException("sizes must be 3 <= min <= max");
            if (!faces.contains(casing)) throw new IllegalArgumentException("the casing must be one of the faces");
        }

        /** A box of 3 to 18 blocks a side, of any width, closed, without a controller. */
        public Box(@NotNull Key casing, @NotNull Set<Key> faces, @NotNull Predicate<Key> interior) {
            this(casing, faces, interior, MIN_SIZE, MAX_SIZE, 0, false, null);
        }

        @Override
        public @NotNull Set<Key> blocks() {
            return this.faces;
        }
    }

    /**
     * A fixed shape, like a crafting recipe in 3D: {@code layers} from the bottom up, each a list of rows
     * from north to south, each row a string of characters from west to east. {@code key} says which
     * blocks each character stands for; a space is any block (not part of the structure). The character
     * {@code anchor} must appear exactly once: that block holds the structure's storages and must be a
     * machine. With {@code rotate}, the shape is also matched turned by 90, 180 and 270 degrees.
     *
     * <pre>{@code
     * layers:                  # a 3x3 ring with a controller, a glass column above it
     *   - ["CCC", "C#C", "CCC"]
     *   - ["   ", " G ", "   "]
     * key: {C: mymod:casing, "#": mymod:controller, G: minecraft:glass}
     * anchor: "#"
     * }</pre>
     */
    record Pattern(@NotNull List<List<String>> layers, @NotNull Map<Character, Set<Key>> key, char anchor, boolean rotate)
            implements MultiblockShape {
        public Pattern {
            layers = layers.stream().map(List::copyOf).toList();
            key = Map.copyOf(key);
            key.values().forEach(Objects::requireNonNull);
            if (layers.isEmpty()) throw new IllegalArgumentException("a pattern needs at least one layer");
            int count = 0;
            for (List<String> layer : layers) {
                for (String row : layer) {
                    for (char c : row.toCharArray()) {
                        if (c == ' ') continue;
                        if (!key.containsKey(c)) throw new IllegalArgumentException("'" + c + "' is not in the key");
                        if (c == anchor) count++;
                    }
                }
            }
            if (count != 1) throw new IllegalArgumentException("the anchor '" + anchor + "' must appear exactly once, found " + count);
        }

        @Override
        public @NotNull Set<Key> blocks() {
            Set<Key> blocks = new HashSet<>();
            this.key.values().forEach(blocks::addAll);
            return Set.copyOf(blocks);
        }

        /** One block of the shape: its offset (x east, y up, z south) and character, unturned. */
        public record Cell(int x, int y, int z, char symbol) {
        }

        /** Every block of the shape (spaces left out). */
        public @NotNull List<Cell> cells() {
            List<Cell> cells = new ArrayList<>();
            for (int y = 0; y < this.layers.size(); y++) {
                List<String> rows = this.layers.get(y);
                for (int z = 0; z < rows.size(); z++) {
                    String row = rows.get(z);
                    for (int x = 0; x < row.length(); x++) {
                        char c = row.charAt(x);
                        if (c != ' ') cells.add(new Cell(x, y, z, c));
                    }
                }
            }
            return cells;
        }
    }
}
