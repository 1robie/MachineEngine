package fr.robie.machineengine.api.multiblock;

import fr.robie.machineengine.api.config.BlockOptions;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A kind of multiblock (D33): its shape, and the capacities its anchor's storages get while it is formed.
 * Registered in {@link fr.robie.machineengine.api.registry.Registries#MULTIBLOCK_TYPE}, so data-driven
 * members can name it; Java members may also use it directly.
 *
 * @param sizes the anchor's storage capacities for a formed structure (per slot), by storage id
 */
public record MultiblockType(@NotNull Key key, @NotNull MultiblockShape shape, @NotNull Sizes sizes) implements Keyed {
    /** The anchor's storage capacities while formed, from the structure and the anchor block's options (D34). */
    @FunctionalInterface
    public interface Sizes {
        @NotNull Map<String, Long> of(@NotNull MultiblockStructure structure, @NotNull BlockOptions anchor);
    }

    public MultiblockType {
        Objects.requireNonNull(key);
        Objects.requireNonNull(shape);
        Objects.requireNonNull(sizes);
    }

    /** A type whose capacities depend on the structure only. */
    public MultiblockType(@NotNull Key key, @NotNull MultiblockShape shape,
                          @NotNull Function<MultiblockStructure, Map<String, Long>> capacities) {
        this(key, shape, (structure, anchor) -> capacities.apply(structure));
        Objects.requireNonNull(capacities);
    }

    /** A type whose anchor's storages keep the capacities their machine type gives them. */
    public MultiblockType(@NotNull Key key, @NotNull MultiblockShape shape) {
        this(key, shape, (structure, anchor) -> Map.of());
    }
}
