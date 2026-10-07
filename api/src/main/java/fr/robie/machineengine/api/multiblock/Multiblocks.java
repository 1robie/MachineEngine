package fr.robie.machineengine.api.multiblock;

import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Finding multiblocks in the world, and the formed ones (D33). Detection only reads blocks, through a
 * {@code blockAt} function ({@link fr.robie.machineengine.api.machine.MachineWorld#blockAt} for a
 * machine), so it runs on the thread that owns them; every block must be loaded and owned.
 */
public final class Multiblocks {
    private static final Map<WorldPosition, MultiblockStructure> FORMED = new ConcurrentHashMap<>();

    private Multiblocks() {
    }

    // -- formed structures -------------------------------------------------------------------------

    /** The formed structure {@code position} belongs to, or {@code null}. */
    public static @Nullable MultiblockStructure containing(@NotNull WorldPosition position) {
        for (MultiblockStructure structure : FORMED.values()) {
            if (structure.contains(position)) return structure;
        }
        return null;
    }

    /** Records a formed structure; one overlapping it (rebuilt bigger, smaller or turned) is replaced. */
    public static void form(@NotNull MultiblockStructure structure) {
        FORMED.values().removeIf(other -> other.overlaps(structure));
        FORMED.put(structure.anchor(), structure);
    }

    public static void unform(@NotNull MultiblockStructure structure) {
        FORMED.remove(structure.anchor(), structure);
    }

    /** Forgets every structure (tests). */
    public static void clear() {
        FORMED.clear();
    }

    // -- detection ---------------------------------------------------------------------------------

    /** {@link #detect(Key, Function, BlockPosition, MultiblockType)} with the machine's own world view. */
    public static @Nullable MultiblockStructure detect(@NotNull MachineContext context, @NotNull WorldPosition start,
                                                       @NotNull MultiblockType type) {
        return detect(start.world(), context.world()::blockAt, start.position(), type);
    }

    /**
     * The structure of {@code type} that the block at {@code start} is part of, or {@code null} if there
     * is no complete one.
     *
     * @param blockAt the block id at a position, {@code null} when not loaded or not owned
     */
    public static @Nullable MultiblockStructure detect(@NotNull Key world, @NotNull Function<BlockPosition, Key> blockAt,
                                                       @NotNull BlockPosition start, @NotNull MultiblockType type) {
        return switch (type.shape()) {
            case MultiblockShape.Box box -> box(world, blockAt, start, type, box);
            case MultiblockShape.Pattern pattern -> pattern(world, blockAt, start, type, pattern);
        };
    }

    /**
     * The frame blocks connected to {@code start} must make exactly the walls of a box within the size
     * limits, with casings on every edge, and an interior the shape allows.
     */
    private static @Nullable MultiblockStructure box(Key world, Function<BlockPosition, Key> blockAt, BlockPosition start,
                                                     MultiblockType type, MultiblockShape.Box shape) {
        Key first = blockAt.apply(start);
        if (first == null || !shape.faces().contains(first)) return null;
        Set<BlockPosition> frame = new HashSet<>();
        ArrayDeque<BlockPosition> queue = new ArrayDeque<>();
        queue.add(start);
        frame.add(start);
        int minX = start.x(), minY = start.y(), minZ = start.z();
        int maxX = minX, maxY = minY, maxZ = minZ;
        while (!queue.isEmpty()) {
            BlockPosition at = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPosition next = at.relative(direction);
                if (frame.contains(next)) continue;
                Key block = blockAt.apply(next);
                if (block == null || !shape.faces().contains(block)) continue;
                minX = Math.min(minX, next.x()); maxX = Math.max(maxX, next.x());
                minY = Math.min(minY, next.y()); maxY = Math.max(maxY, next.y());
                minZ = Math.min(minZ, next.z()); maxZ = Math.max(maxZ, next.z());
                if (maxX - minX >= shape.maxSize() || maxY - minY >= shape.maxSize() || maxZ - minZ >= shape.maxSize()) return null;
                frame.add(next);
                queue.add(next);
            }
        }
        int min = shape.minSize();
        if (maxX - minX + 1 < min || maxY - minY + 1 < min || maxZ - minZ + 1 < min) return null;
        if (shape.width() > 0 && (maxX - minX + 1 != shape.width() || maxZ - minZ + 1 != shape.width())) return null;
        Map<Key, Integer> interior = new HashMap<>();
        int controllers = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPosition at = new BlockPosition(x, y, z);
                    // An open top has no roof: its top layer is inside, walls round it.
                    boolean yBound = y == minY || (y == maxY && !shape.openTop());
                    int onBounds = (x == minX || x == maxX ? 1 : 0) + (yBound ? 1 : 0) + (z == minZ || z == maxZ ? 1 : 0);
                    if (onBounds == 0) {
                        Key block = blockAt.apply(at);
                        if (block == null || !shape.interior().test(block)) return null;
                        interior.merge(block, 1, Integer::sum);
                    } else if (!frame.contains(at)) {
                        return null; // a hole in the walls
                    } else if (onBounds >= 2 && !shape.casing().equals(blockAt.apply(at))) {
                        return null; // edges and corners are casings
                    } else if (shape.controller() != null && shape.controller().equals(blockAt.apply(at))) {
                        controllers++;
                    }
                }
            }
        }
        if (shape.controller() != null && controllers != 1) return null; // exactly one controller
        BlockPosition low = new BlockPosition(minX, minY, minZ);
        return new MultiblockStructure(type, world, low, new BlockPosition(maxX, maxY, maxZ), low, null, 0, interior);
    }

    /**
     * Tries every way the pattern can hold {@code start}: each of its cells that allows that block, in
     * each rotation; the first complete match wins.
     */
    private static @Nullable MultiblockStructure pattern(Key world, Function<BlockPosition, Key> blockAt, BlockPosition start,
                                                         MultiblockType type, MultiblockShape.Pattern shape) {
        Key first = blockAt.apply(start);
        if (first == null) return null;
        List<MultiblockShape.Pattern.Cell> cells = shape.cells();
        int rotations = shape.rotate() ? 4 : 1;
        for (int rotation = 0; rotation < rotations; rotation++) {
            for (MultiblockShape.Pattern.Cell pivot : cells) {
                if (!shape.key().get(pivot.symbol()).contains(first)) continue;
                int[] turned = turn(pivot.x(), pivot.z(), rotation);
                BlockPosition origin = new BlockPosition(start.x() - turned[0], start.y() - pivot.y(), start.z() - turned[1]);
                MultiblockStructure found = match(world, blockAt, origin, rotation, type, shape, cells);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static @Nullable MultiblockStructure match(Key world, Function<BlockPosition, Key> blockAt, BlockPosition origin,
                                                       int rotation, MultiblockType type, MultiblockShape.Pattern shape,
                                                       List<MultiblockShape.Pattern.Cell> cells) {
        Set<BlockPosition> members = new HashSet<>();
        Map<Key, Integer> counts = new HashMap<>();
        BlockPosition anchor = null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (MultiblockShape.Pattern.Cell cell : cells) {
            int[] turned = turn(cell.x(), cell.z(), rotation);
            BlockPosition at = new BlockPosition(origin.x() + turned[0], origin.y() + cell.y(), origin.z() + turned[1]);
            Key block = blockAt.apply(at);
            if (block == null || !shape.key().get(cell.symbol()).contains(block)) return null;
            members.add(at);
            counts.merge(block, 1, Integer::sum);
            if (cell.symbol() == shape.anchor()) anchor = at;
            minX = Math.min(minX, at.x()); minY = Math.min(minY, at.y()); minZ = Math.min(minZ, at.z());
            maxX = Math.max(maxX, at.x()); maxY = Math.max(maxY, at.y()); maxZ = Math.max(maxZ, at.z());
        }
        return new MultiblockStructure(type, world, new BlockPosition(minX, minY, minZ), new BlockPosition(maxX, maxY, maxZ),
                anchor, members, rotation, counts);
    }

    /** An offset east ({@code x}) and south ({@code z}) turned {@code quarters} times clockwise, seen from above. */
    static int[] turn(int x, int z, int quarters) {
        int tx = x, tz = z;
        for (int i = 0; i < quarters; i++) {
            int east = -tz;
            tz = tx;
            tx = east;
        }
        return new int[] {tx, tz};
    }
}
