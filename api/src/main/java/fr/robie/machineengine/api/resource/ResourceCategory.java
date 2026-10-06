package fr.robie.machineengine.api.resource;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A family of resources, e.g. {@code energyplugin:energy} or {@code mymod:fluid}
 * (ARCHITECTURE.md §9.1). MachineEngine attaches no meaning to categories.
 *
 * <p>Amounts are {@code long}s in {@link #unit() base units} (D10). {@link #scale()} is the number
 * of decimal digits used when displaying them: a scale of 3 shows 1500 base units as 1.500.</p>
 *
 * <p>Definitions are canonical: compare them by reference.</p>
 */
public final class ResourceCategory implements Keyed {
    private final Key key;
    private final String unit;
    private final int scale;
    private final MergePolicy mergePolicy;

    private ResourceCategory(Builder builder) {
        this.key = builder.key;
        this.unit = builder.unit;
        this.scale = builder.scale;
        this.mergePolicy = builder.mergePolicy;
    }

    public static @NotNull Builder builder(@NotNull Key key) {
        return new Builder(key);
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** Name of the base unit, e.g. {@code mB} or {@code FE}. Display only. */
    public @NotNull String unit() {
        return this.unit;
    }

    /** Decimal digits used to display amounts. */
    public int scale() {
        return this.scale;
    }

    /** How two stacks of one type with different components combine in a storage. */
    public @NotNull MergePolicy mergePolicy() {
        return this.mergePolicy;
    }

    @Override
    public String toString() {
        return "ResourceCategory[" + this.key.asString() + "]";
    }

    public static final class Builder {
        private final Key key;
        private String unit = "";
        private int scale;
        private MergePolicy mergePolicy = MergePolicy.STRICT;

        private Builder(Key key) {
            this.key = Objects.requireNonNull(key);
        }

        public @NotNull Builder unit(@NotNull String unit) {
            this.unit = unit;
            return this;
        }

        public @NotNull Builder scale(int scale) {
            if (scale < 0 || scale > 18) throw new IllegalArgumentException("scale must be within 0..18 but is " + scale);
            this.scale = scale;
            return this;
        }

        public @NotNull Builder mergePolicy(@NotNull MergePolicy mergePolicy) {
            this.mergePolicy = Objects.requireNonNull(mergePolicy);
            return this;
        }

        public @NotNull ResourceCategory build() {
            return new ResourceCategory(this);
        }
    }
}
