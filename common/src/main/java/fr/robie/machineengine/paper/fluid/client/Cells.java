package fr.robie.machineengine.paper.fluid.client;

/** Block positions and biome cells packed into a {@code long} like Minecraft's {@code BlockPos.asLong}; chunk keys. */
public final class Cells {
    private Cells() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    public static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }

    public static long chunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long chunk) {
        return (int) (chunk >> 32);
    }

    public static int chunkZ(long chunk) {
        return (int) chunk;
    }
}
