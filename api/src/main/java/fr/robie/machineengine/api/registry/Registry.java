package fr.robie.machineengine.api.registry;

import fr.robie.machineengine.api.addon.AddonId;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    /** The entry {@code id}, or the entry an {@link #aliases() alias} {@code id} points to. */
    @NotNull Optional<T> get(@NotNull Key id);

    default @NotNull T getOrThrow(@NotNull Key id) {
        return get(id).orElseThrow(() -> new NoSuchElementException("No entry " + id.asString() + " in registry " + type()));
    }

    /** Whether {@link #get} finds {@code id}, as an entry or an alias. */
    boolean contains(@NotNull Key id);

    /** @return whether this exact (reference-equal) object is registered */
    boolean containsValue(@NotNull T value);

    /** The addon that registered the entry or the alias {@code id}. */
    @NotNull Optional<AddonId> owner(@NotNull Key id);

    /** The keys of the entries; aliases are not included. */
    @NotNull Set<Key> keys();

    /** Old keys and the entry each one now means (see {@link WritableRegistry#alias}). */
    default @NotNull Map<Key, Key> aliases() {
        return Map.of();
    }

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
