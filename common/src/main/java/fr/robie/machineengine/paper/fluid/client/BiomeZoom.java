package fr.robie.machineengine.paper.fluid.client;

import com.google.common.hash.Hashing;

/**
 * Which 4×4×4 biome cell the client reads a block's biome (and so its water color) from. A copy of
 * vanilla's {@code BiomeManager.getBiome}, unchanged from 1.20.6 to 26.3: the block looks at the 8 cells
 * around it and takes the nearest after a seeded jitter, so it is not always the cell the block is in.
 * Checked against vanilla on 2,000 random positions by the world fluids spike.
 */
public final class BiomeZoom {
    private BiomeZoom() {
    }

    /** The seed the client zooms with: the world seed hashed, as the server sends it at login ({@code BiomeManager.obfuscateSeed}). */
    public static long zoomSeed(long worldSeed) {
        return Hashing.sha256().hashLong(worldSeed).asLong();
    }

    /** The cell (packed with {@link Cells#pack}) whose biome the client uses at this block. */
    public static long cell(long seed, int blockX, int blockY, int blockZ) {
        int x = blockX - 2, y = blockY - 2, z = blockZ - 2;
        int qx = x >> 2, qy = y >> 2, qz = z >> 2;
        double fx = (x & 3) / 4.0, fy = (y & 3) / 4.0, fz = (z & 3) / 4.0;
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            boolean lx = (i & 4) == 0, ly = (i & 2) == 0, lz = (i & 1) == 0;
            double distance = fiddledDistance(seed, lx ? qx : qx + 1, ly ? qy : qy + 1, lz ? qz : qz + 1,
                    lx ? fx : fx - 1.0, ly ? fy : fy - 1.0, lz ? fz : fz - 1.0);
            if (bestDistance > distance) {
                best = i;
                bestDistance = distance;
            }
        }
        return Cells.pack((best & 4) == 0 ? qx : qx + 1, (best & 2) == 0 ? qy : qy + 1, (best & 1) == 0 ? qz : qz + 1);
    }

    private static double fiddledDistance(long seed, int x, int y, int z, double dx, double dy, double dz) {
        long s = next(seed, x);
        s = next(s, y);
        s = next(s, z);
        s = next(s, x);
        s = next(s, y);
        s = next(s, z);
        double jx = fiddle(s);
        s = next(s, seed);
        double jy = fiddle(s);
        s = next(s, seed);
        double jz = fiddle(s);
        return square(dz + jz) + square(dy + jy) + square(dx + jx);
    }

    private static double fiddle(long s) {
        double d = Math.floorMod(s >> 24, 1024) / 1024.0;
        return (d - 0.5) * 0.9;
    }

    /** {@code LinearCongruentialGenerator.next}. */
    private static long next(long value, long add) {
        value *= value * 6364136223846793005L + 1442695040888963407L;
        return value + add;
    }

    private static double square(double d) {
        return d * d;
    }
}
