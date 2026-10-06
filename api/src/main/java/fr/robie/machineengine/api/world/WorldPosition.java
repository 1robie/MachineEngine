package fr.robie.machineengine.api.world;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/** A block position in a world, identified by the world's key (e.g. {@code minecraft:overworld}). */
public record WorldPosition(@NotNull Key world, @NotNull BlockPosition position) {

    public static @NotNull WorldPosition of(@NotNull Key world, int x, int y, int z) {
        return new WorldPosition(world, new BlockPosition(x, y, z));
    }

    public @NotNull WorldPosition relative(@NotNull Direction direction) {
        return new WorldPosition(this.world, this.position.relative(direction));
    }

    @Override
    public @NotNull String toString() {
        return this.world.asString() + " " + this.position;
    }
}
