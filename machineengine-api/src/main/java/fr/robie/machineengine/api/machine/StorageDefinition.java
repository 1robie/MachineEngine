package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A storage of a machine type: {@code slots} slots of {@code capacity} each, accepting variants
 * matching {@code filter} (ARCHITECTURE.md §12). The engine creates and persists it.
 *
 * @param id unique within the machine type, e.g. {@code input}
 */
public record StorageDefinition(@NotNull String id, int slots, long capacity, @NotNull ResourcePredicate filter) {

    public StorageDefinition {
        Objects.requireNonNull(id);
        Objects.requireNonNull(filter);
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
        return new StorageDefinition(this.id, this.slots, this.capacity, filter);
    }
}
