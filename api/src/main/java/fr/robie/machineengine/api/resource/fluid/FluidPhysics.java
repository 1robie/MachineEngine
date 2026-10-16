package fr.robie.machineengine.api.resource.fluid;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * How a placed fluid behaves in the world. Read at runtime, so {@code fluids.yml} changes apply on
 * {@code /machineengine reload fluids}.
 *
 * @param viscosity      how it moves things inside it
 * @param spread         how fast and how far it flows
 * @param infiniteSource whether two of its sources make a new source of it, like water
 * @param mixedSource    what a source formed between it and another fluid becomes
 */
public record FluidPhysics(@NotNull Viscosity viscosity, @NotNull Spread spread, boolean infiniteSource,
                           @NotNull MixedSource mixedSource) {
    /** Water's behavior; a source between two different fluids forms nothing. */
    public static final FluidPhysics WATER = new FluidPhysics(Viscosity.WATER, Spread.WATER, true, MixedSource.NONE);

    public FluidPhysics {
        Objects.requireNonNull(viscosity);
        Objects.requireNonNull(spread);
        Objects.requireNonNull(mixedSource);
    }

    /** What a source formed between two different fluids (or a fluid and plain water) becomes. */
    public enum MixedSource {
        /** Nothing: the block stays flowing. */
        NONE,
        /** Plain water. */
        WATER
    }
}
