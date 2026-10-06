package fr.robie.machineengine.api.registry;

import fr.robie.machineengine.api.addon.AddonId;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read view of a registry. Iteration order is registration order (deterministic, §19).
 * Thread-safe once frozen.
 */
@ApiStatus.NonExtendable
public interface Registry<T extends Keyed> extends Iterable<T> {

    @NotNull RegistryType<T> type();

    @NotNull Optional<T> get(@NotNull Key id);

    default @NotNull T getOrThrow(@NotNull Key id) {
        return get(id).orElseThrow(() -> new NoSuchElementException("No entry " + id.asString() + " in registry " + type()));
    }

    boolean contains(@NotNull Key id);

    /** @return whether this exact (reference-equal) object is registered */
    boolean containsValue(@NotNull T value);

    @NotNull Optional<AddonId> owner(@NotNull Key id);

    @NotNull Set<Key> keys();

    @NotNull List<T> values();

    default @NotNull Stream<T> stream() {
        return values().stream();
    }

    default int size() {
        return values().size();
    }

    @Override
    default @NotNull Iterator<T> iterator() {
        return values().iterator();
    }
}
