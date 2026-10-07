package fr.robie.machineengine.api.multiblock;

import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * A formed multiblock (D33).
 *
 * @param min      the lowest corner of its bounding box (walls included)
 * @param max      the highest corner of its bounding box
 * @param anchor   the block holding its storages: a box's lowest corner, a pattern's anchor character
 * @param members  the blocks that make it, for a {@link MultiblockShape.Pattern}; {@code null} for a box,
 *                 which is made of its whole bounding box
 * @param rotation quarter turns clockwise (seen from above) a pattern was matched with; 0 for a box
 * @param counts   for a box, what its interior holds, by block id; for a pattern, its blocks, by block id
 */
public record MultiblockStructure(@NotNull MultiblockType type, @NotNull Key world, @NotNull BlockPosition min,
                                  @NotNull BlockPosition max, @NotNull BlockPosition anchorPosition,
                                  @Nullable Set<BlockPosition> members, int rotation, @NotNull Map<Key, Integer> counts) {
    public MultiblockStructure {
        members = members == null ? null : Set.copyOf(members);
        counts = Map.copyOf(counts);
    }

    public @NotNull WorldPosition anchor() {
        return new WorldPosition(this.world, this.anchorPosition);
    }

    /** Whether {@code position} is part of the structure (for a box: anywhere in it, interior included). */
    public boolean contains(@NotNull WorldPosition position) {
        if (!position.world().equals(this.world) || !inBounds(position.position())) return false;
        return this.members == null || this.members.contains(position.position());
    }

    private boolean inBounds(BlockPosition at) {
        return at.x() >= this.min.x() && at.x() <= this.max.x() && at.y() >= this.min.y() && at.y() <= this.max.y()
                && at.z() >= this.min.z() && at.z() <= this.max.z();
    }

    /** Whether it shares a block with {@code other}. */
    public boolean overlaps(@NotNull MultiblockStructure other) {
        if (!this.world.equals(other.world)) return false;
        boolean boxes = this.min.x() <= other.max.x() && other.min.x() <= this.max.x() && this.min.y() <= other.max.y()
                && other.min.y() <= this.max.y() && this.min.z() <= other.max.z() && other.min.z() <= this.max.z();
        if (!boxes) return false;
        if (this.members == null) return other.members == null || other.members.stream().anyMatch(this::inBounds);
        if (other.members == null) return this.members.stream().anyMatch(other::inBounds);
        return this.members.stream().anyMatch(other.members::contains);
    }

    /** A box's interior volume in blocks; for a pattern, its number of blocks. */
    public int volume() {
        if (this.members != null) return this.members.size();
        boolean openTop = this.type.shape() instanceof MultiblockShape.Box box && box.openTop();
        int height = this.max.y() - this.min.y() + 1 - (openTop ? 1 : 2);
        return (this.max.x() - this.min.x() - 1) * height * (this.max.z() - this.min.z() - 1);
    }

    /** Outer height in blocks. */
    public int height() {
        return this.max.y() - this.min.y() + 1;
    }

    /** How many {@code block} it counts ({@link #counts}). */
    public int count(@NotNull Key block) {
        return this.counts.getOrDefault(block, 0);
    }
}
