package fr.robie.machineengine.api.multiblock;

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
 * @param capacities the anchor's storage capacities for a formed structure (per slot), by storage id
 */
public record MultiblockType(@NotNull Key key, @NotNull MultiblockShape shape,
                             @NotNull Function<MultiblockStructure, Map<String, Long>> capacities) implements Keyed {
    public MultiblockType {
        Objects.requireNonNull(key);
        Objects.requireNonNull(shape);
        Objects.requireNonNull(capacities);
    }

    /** A type whose anchor's storages keep the capacities their machine type gives them. */
    public MultiblockType(@NotNull Key key, @NotNull MultiblockShape shape) {
        this(key, shape, structure -> Map.of());
    }
}
