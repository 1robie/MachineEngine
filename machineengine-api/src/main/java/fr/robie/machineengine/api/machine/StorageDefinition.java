package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A storage of a machine type: {@code slots} slots of {@code capacity} each, accepting variants
 * matching {@code filter} (ARCHITECTURE.md §12). The engine creates and persists it.
 *
 * @param id               unique within the machine type, e.g. {@code input}
 * @param capacityModifier scales each slot's capacity with the machine's upgrades (e.g. Mekanism's
 *                         energy upgrade enlarging the energy buffer), or {@code null}
 */
public record StorageDefinition(@NotNull String id, int slots, long capacity, @NotNull ResourcePredicate filter,
                                @org.jetbrains.annotations.Nullable fr.robie.machineengine.api.upgrade.ModifierType capacityModifier) {

    public StorageDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(filter);
    }

    public StorageDefinition(@NotNull String id, int slots, long capacity, @NotNull ResourcePredicate filter) {
        this(id, slots, capacity, filter, null);
    }

    /** Each slot's capacity follows {@code modifier} (applied to {@link #capacity()} with the machine's upgrades). */
    public @NotNull StorageDefinition capacityModifier(@NotNull fr.robie.machineengine.api.upgrade.ModifierType modifier) {
        return new StorageDefinition(this.id, this.slots, this.capacity, this.filter, modifier);
    }

    /** {@code count} slots of {@code capacity} each, accepting anything. */
    public static @NotNull StorageDefinition slots(@NotNull String id, int count, long capacity) {
        return new StorageDefinition(id, count, capacity, ResourcePredicate.any());
    }

    /** A single slot, e.g. a tank or battery. */
    public static @NotNull StorageDefinition tank(@NotNull String id, long capacity) {
        return slots(id, 1, capacity);
    }

    public @NotNull StorageDefinition filter(@NotNull ResourcePredicate filter) {
        return new StorageDefinition(this.id, this.slots, this.capacity, filter, this.capacityModifier);
    }
}
