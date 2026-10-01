package fr.robie.machineengine.api.upgrade;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A numeric value machines let upgrades modify (ARCHITECTURE.md §14), e.g. process duration.
 * Results are clamped to {@code [min, max]}.
 */
public final class ModifierType implements Keyed {
    private final Key key;
    private final long min;
    private final long max;

    private ModifierType(Key key, long min, long max) {
        this.key = key;
        this.min = min;
        this.max = max;
    }

    public static @NotNull ModifierType of(@NotNull Key key, long min, long max) {
        if (min > max) throw new IllegalArgumentException("min " + min + " > max " + max);
        return new ModifierType(Objects.requireNonNull(key), min, max);
    }

    /** A modifier type whose results are never negative. */
    public static @NotNull ModifierType nonNegative(@NotNull Key key) {
        return of(key, 0, Long.MAX_VALUE);
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public long min() {
        return this.min;
    }

    public long max() {
        return this.max;
    }

    @Override
    public String toString() {
        return "ModifierType[" + this.key.asString() + "]";
    }
}
