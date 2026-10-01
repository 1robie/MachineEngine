package fr.robie.machineengine.api.world;

import org.jetbrains.annotations.NotNull;

/** A block position. */
public record BlockPosition(int x, int y, int z) {

    public @NotNull BlockPosition relative(@NotNull Direction direction) {
        return new BlockPosition(this.x + direction.dx(), this.y + direction.dy(), this.z + direction.dz());
    }

    public int chunkX() {
        return this.x >> 4;
    }

    public int chunkZ() {
        return this.z >> 4;
    }

    @Override
    public @NotNull String toString() {
        return this.x + " " + this.y + " " + this.z;
    }
}
