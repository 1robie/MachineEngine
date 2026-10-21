package fr.robie.machineengine.core.machine;

import java.util.random.RandomGenerator;

/**
 * A machine's random (chance outputs, behaviors), saved with the machine: SplitMix64, the generator
 * behind {@link java.util.SplittableRandom}, whose whole state is one long. Without its state saved,
 * every load replayed the same rolls, so reloading the chunk between processes could repeat a lucky one.
 */
final class MachineRandom implements RandomGenerator {
    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    MachineRandom(long seed) {
        this.state = seed;
    }

    long state() {
        return this.state;
    }

    void setState(long state) {
        this.state = state;
    }

    @Override
    public long nextLong() {
        long z = this.state += GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
