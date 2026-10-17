package fr.robie.machineengine.paper.fluid.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * Which water block holds which placeable fluid, and which key each biome cell gets (D46). Read from
 * Netty threads by the biome remapper, so everything is concurrent. Fluids are numbered 1..n by
 * {@link FluidKeys}; 0 is plain water.
 *
 * <p>A cell's <em>readers</em> are the blocks that take their color from it ({@link BiomeZoom}). A cell is
 * <em>owned</em> by the fluid most of its tracked readers hold, and gets that fluid's key. Without uniform
 * water, the cells within {@code neutralRings} of an owned cell get the neutral water. Padding and
 * dispersion cells (around owned cells) are computed on region threads and stored here.</p>
 */
public final class FluidCells {
    private final ToLongFunction<UUID> zoomSeed;
    private final FluidKeys keys;
    private final int neutralRings;
    private final Map<UUID, Map<Long, Integer>> blocks = new ConcurrentHashMap<>();
    /** Cell → tracked readers per fluid (index = fluid number; index 0 unused). */
    private final Map<UUID, Map<Long, int[]>> readers = new ConcurrentHashMap<>();
    /** Cell → key entry, for padding and dispersion cells. */
    private final Map<UUID, Map<Long, Integer>> padding = new ConcurrentHashMap<>();
    /** Cell → number of owned cells within neutralRings (above 0: the neutral water). */
    private final Map<UUID, Map<Long, Integer>> neutral = new ConcurrentHashMap<>();
    /** Sections near an owned cell, so the remapper skips most sections at once. */
    private final Map<UUID, Set<Long>> sections = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Set<Long>>> chunks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> unsaved = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Long>> dirtyCells = new ConcurrentHashMap<>();
    /** One lock per world: on Folia two region threads can change cells on their shared border. */
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    /**
     * @param zoomSeed     the zoom seed of a world ({@link BiomeZoom#zoomSeed})
     * @param neutralRings rings of neutral cells around owned cells; 0 with uniform water
     */
    public FluidCells(@NotNull ToLongFunction<UUID> zoomSeed, @NotNull FluidKeys keys, int neutralRings) {
        this.zoomSeed = zoomSeed;
        this.keys = keys;
        this.neutralRings = neutralRings;
    }

    public @NotNull FluidKeys keys() {
        return this.keys;
    }

    /** Rings of neutral cells around owned cells (0 with uniform water), fixed at start. */
    public int neutralRings() {
        return this.neutralRings;
    }

    private Object lock(UUID world) {
        return this.locks.computeIfAbsent(world, ignored -> new Object());
    }

    /** The cell the client reads this block's color from. */
    public long readCell(@NotNull UUID world, int x, int y, int z) {
        return BiomeZoom.cell(this.zoomSeed.applyAsLong(world), x, y, z);
    }

    /** The fluid number of a block; 0 for none. */
    public int get(@NotNull UUID world, int x, int y, int z) {
        Map<Long, Integer> map = this.blocks.get(world);
        return map == null ? 0 : map.getOrDefault(Cells.pack(x, y, z), 0);
    }

    /** @return the cell whose owner changed (its key must be resent), or {@code null} */
    public @Nullable Long set(@NotNull UUID world, int x, int y, int z, int fluid) {
        if (fluid == 0) return clear(world, x, y, z);
        synchronized (lock(world)) {
            return setLocked(world, x, y, z, fluid);
        }
    }

    private @Nullable Long setLocked(UUID world, int x, int y, int z, int fluid) {
        long block = Cells.pack(x, y, z);
        Integer previous = this.blocks.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>()).put(block, fluid);
        if (previous != null && previous == fluid) return null;
        long chunk = Cells.chunk(x >> 4, z >> 4);
        this.chunks.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunk, ignored -> ConcurrentHashMap.newKeySet()).add(block);
        this.unsaved.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet()).add(chunk);
        long cell = readCell(world, x, y, z);
        int ownerBefore = owner(world, cell);
        this.readers.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>()).compute(cell, (key, count) -> {
            int[] c = count == null ? new int[this.keys.fluidCount() + 1] : count.clone();
            if (previous != null) c[previous]--;
            c[fluid]++;
            return c;
        });
        int rings = Math.max(this.neutralRings, FluidKeys.MAX_RINGS);
        Set<Long> marked = this.sections.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet());
        int cx = Cells.x(cell), cy = Cells.y(cell), cz = Cells.z(cell);
        for (int sx = (cx - rings) >> 2; sx <= (cx + rings) >> 2; sx++)
            for (int sy = (cy - rings) >> 2; sy <= (cy + rings) >> 2; sy++)
                for (int sz = (cz - rings) >> 2; sz <= (cz + rings) >> 2; sz++)
                    marked.add(Cells.pack(sx, sy, sz));
        return ownerChanged(world, cell, ownerBefore);
    }

    /** @return the cell whose owner changed (its key must be resent), or {@code null} */
    public @Nullable Long clear(@NotNull UUID world, int x, int y, int z) {
        synchronized (lock(world)) {
            return clearLocked(world, x, y, z);
        }
    }

    private @Nullable Long clearLocked(UUID world, int x, int y, int z) {
        Map<Long, Integer> map = this.blocks.get(world);
        long block = Cells.pack(x, y, z);
        Integer previous = map == null ? null : map.remove(block);
        if (previous == null) return null;
        long chunk = Cells.chunk(x >> 4, z >> 4);
        Map<Long, Set<Long>> byChunk = this.chunks.get(world);
        if (byChunk != null) {
            Set<Long> inChunk = byChunk.get(chunk);
            if (inChunk != null) inChunk.remove(block);
        }
        this.unsaved.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet()).add(chunk);
        long cell = readCell(world, x, y, z);
        int ownerBefore = owner(world, cell);
        this.readers.get(world).computeIfPresent(cell, (key, count) -> {
            int[] c = count.clone();
            c[previous]--;
            for (int n : c) if (n > 0) return c;
            return null;
        });
        return ownerChanged(world, cell, ownerBefore);
    }

    private @Nullable Long ownerChanged(UUID world, long cell, int ownerBefore) {
        int ownerAfter = owner(world, cell);
        if (ownerAfter == ownerBefore) return null;
        if (this.neutralRings > 0 && (ownerBefore == 0) != (ownerAfter == 0)) {
            int delta = ownerAfter != 0 ? 1 : -1;
            Map<Long, Integer> refs = this.neutral.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
            int cx = Cells.x(cell), cy = Cells.y(cell), cz = Cells.z(cell);
            for (int dx = -this.neutralRings; dx <= this.neutralRings; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -this.neutralRings; dz <= this.neutralRings; dz++)
                        refs.merge(Cells.pack(cx + dx, cy + dy, cz + dz), delta, (a, b) -> a + b == 0 ? null : a + b);
        }
        this.dirtyCells.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet()).add(cell);
        return cell;
    }

    /**
     * Drops an unloaded chunk from memory (its blocks were saved): its blocks, and the padding and section
     * marks of its cells, so nothing stale is shown when it loads again and memory does not grow.
     */
    public void forgetChunk(@NotNull UUID world, int chunkX, int chunkZ) {
        for (long block : blocksIn(world, chunkX, chunkZ)) clear(world, Cells.x(block), Cells.y(block), Cells.z(block));
        Map<Long, Set<Long>> byChunk = this.chunks.get(world);
        if (byChunk != null) byChunk.remove(Cells.chunk(chunkX, chunkZ));
        Set<Long> pending = this.unsaved.get(world);
        if (pending != null) pending.remove(Cells.chunk(chunkX, chunkZ));
        Map<Long, Integer> pads = this.padding.get(world);
        if (pads != null) pads.keySet().removeIf(cell -> Cells.x(cell) >> 2 == chunkX && Cells.z(cell) >> 2 == chunkZ);
        Set<Long> marked = this.sections.get(world);
        if (marked != null) marked.removeIf(section -> Cells.x(section) == chunkX && Cells.z(section) == chunkZ);
    }

    /**
     * A chunk loaded: the owned cells within reach of it get their surroundings (padding, dispersion)
     * computed again, so its cells take the keys of fluids next to it.
     */
    public void markDirtyAround(@NotNull UUID world, int chunkX, int chunkZ) {
        Map<Long, int[]> map = this.readers.get(world);
        if (map == null) return;
        // A padded cell's readers reach 5 blocks past it, so a cell up to one ring outside the chunk reads
        // into it; owned cells up to MAX_RINGS beyond that pad it.
        int reach = FluidKeys.MAX_RINGS + 1;
        int minX = chunkX * 4 - reach, maxX = chunkX * 4 + 3 + reach, minZ = chunkZ * 4 - reach, maxZ = chunkZ * 4 + 3 + reach;
        Set<Long> dirty = this.dirtyCells.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet());
        for (long cell : map.keySet()) {
            int x = Cells.x(cell), z = Cells.z(cell);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) dirty.add(cell);
        }
    }

    /** Chunks whose blocks changed since the last call (to write into the chunk). */
    public @NotNull List<Long> takeUnsavedChunks(@NotNull UUID world) {
        return take(this.unsaved.get(world));
    }

    /** Owned cells changed since the last call (their padding is recomputed). */
    public @NotNull List<Long> takeDirtyCells(@NotNull UUID world) {
        return take(this.dirtyCells.get(world));
    }

    private static List<Long> take(@Nullable Set<Long> set) {
        if (set == null || set.isEmpty()) return List.of();
        List<Long> taken = List.copyOf(set);
        taken.forEach(set::remove);
        return taken;
    }

    public void clearAll() {
        this.blocks.clear();
        this.readers.clear();
        this.padding.clear();
        this.neutral.clear();
        this.sections.clear();
        this.chunks.clear();
        this.dirtyCells.clear();
        this.unsaved.clear();
    }

    public int size(@NotNull UUID world) {
        Map<Long, Integer> map = this.blocks.get(world);
        return map == null ? 0 : map.size();
    }

    public boolean hasSection(@NotNull UUID world, int sectionX, int sectionY, int sectionZ) {
        Set<Long> set = this.sections.get(world);
        return set != null && set.contains(Cells.pack(sectionX, sectionY, sectionZ));
    }

    /** The fluid most of a cell's tracked readers hold (ties: the lowest number); 0 for none. */
    public int owner(@NotNull UUID world, long cell) {
        Map<Long, int[]> map = this.readers.get(world);
        int[] count = map == null ? null : map.get(cell);
        if (count == null) return 0;
        int best = 0;
        for (int fluid = 1; fluid < count.length; fluid++) {
            if (count[fluid] > 0 && (best == 0 || count[fluid] > count[best])) best = fluid;
        }
        return best;
    }

    /** Whether a cell has tracked readers of another fluid than {@code fluid}. */
    public boolean readByOtherFluid(@NotNull UUID world, long cell, int fluid) {
        Map<Long, int[]> map = this.readers.get(world);
        int[] count = map == null ? null : map.get(cell);
        if (count == null) return false;
        for (int other = 1; other < count.length; other++) {
            if (other != fluid && count[other] > 0) return true;
        }
        return false;
    }

    /** A fluid other than {@code fluid} owning a cell within {@code rings} (sideways, one layer up and down); 0 for none. */
    public int otherOwnerNear(@NotNull UUID world, long cell, int rings, int fluid) {
        int cx = Cells.x(cell), cy = Cells.y(cell), cz = Cells.z(cell);
        for (int dx = -rings; dx <= rings; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -rings; dz <= rings; dz++) {
                    int other = owner(world, Cells.pack(cx + dx, cy + dy, cz + dz));
                    if (other != 0 && other != fluid) return other;
                }
        return 0;
    }

    /** {fluid, distance in cells} of the nearest owned cell within {@code rings} (in the same layer when {@code sideways}); fluid 0 for none. */
    public int @NotNull [] nearestOwner(@NotNull UUID world, long cell, int rings, boolean sideways) {
        int cx = Cells.x(cell), cy = Cells.y(cell), cz = Cells.z(cell);
        for (int r = 1; r <= rings; r++) {
            int ry = sideways ? 0 : r;
            for (int dx = -r; dx <= r; dx++) for (int dy = -ry; dy <= ry; dy++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                int fluid = owner(world, Cells.pack(cx + dx, cy + dy, cz + dz));
                if (fluid != 0) return new int[]{fluid, r};
            }
        }
        return new int[]{0, 0};
    }

    /** The key entry of a cell ({@link FluidKeys#entry}, {@link FluidKeys#neutralEntry}); 0 to keep the cell's own biome. */
    public int key(@NotNull UUID world, long cell) {
        int owner = owner(world, cell);
        if (owner != 0) return this.keys.entry(owner, 0);
        Map<Long, Integer> pads = this.padding.get(world);
        Integer pad = pads == null ? null : pads.get(cell);
        if (pad != null) return pad;
        Map<Long, Integer> refs = this.neutral.get(world);
        return refs != null && refs.containsKey(cell) ? this.keys.neutralEntry() : 0;
    }

    /** @param entry a key entry ({@link FluidKeys#entry}), 0 for none */
    public void setPadding(@NotNull UUID world, long cell, int entry) {
        Map<Long, Integer> pads = this.padding.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>());
        if (entry == 0) pads.remove(cell);
        else pads.put(cell, entry);
    }

    /** Marks every owned cell for a padding recompute (after a setting changes). */
    public void markAllDirty(@NotNull UUID world) {
        Map<Long, int[]> map = this.readers.get(world);
        if (map != null) this.dirtyCells.computeIfAbsent(world, ignored -> ConcurrentHashMap.newKeySet()).addAll(map.keySet());
    }

    public void clearPadding() {
        this.padding.clear();
    }

    /** Chunks (packed with {@link Cells#chunk}) holding tracked blocks. */
    public @NotNull List<Long> trackedChunks(@NotNull UUID world) {
        Map<Long, Set<Long>> map = this.chunks.get(world);
        if (map == null) return List.of();
        map.values().removeIf(Set::isEmpty);
        return List.copyOf(map.keySet());
    }

    /** Tracked blocks (packed with {@link Cells#pack}) of one chunk. */
    public @NotNull List<Long> blocksIn(@NotNull UUID world, int chunkX, int chunkZ) {
        Map<Long, Set<Long>> map = this.chunks.get(world);
        if (map == null) return List.of();
        Set<Long> set = map.get(Cells.chunk(chunkX, chunkZ));
        return set == null ? List.of() : List.copyOf(set);
    }
}
