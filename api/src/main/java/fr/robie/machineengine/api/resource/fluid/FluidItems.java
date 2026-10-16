package fr.robie.machineengine.api.resource.fluid;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** The items that hold a placed fluid. */
public record FluidItems(@NotNull Bucket bucket) {
    /** A generated bucket. */
    public static final FluidItems AUTO = new FluidItems(Bucket.Auto.INSTANCE);
    public static final FluidItems NONE = new FluidItems(Bucket.None.INSTANCE);

    public FluidItems {
        Objects.requireNonNull(bucket);
    }

    /** The fluid's bucket. */
    public sealed interface Bucket {
        /** No bucket: the fluid can't be picked up or poured by hand. */
        enum None implements Bucket { INSTANCE }

        /** A CraftEngine item MachineEngine generates (from a template, D36), tinted with the fluid. */
        enum Auto implements Bucket { INSTANCE }

        /** An existing CraftEngine item, e.g. {@code mymod:oil_bucket}. */
        record Item(@NotNull Key item) implements Bucket {
            public Item {
                Objects.requireNonNull(item);
            }
        }
    }
}
