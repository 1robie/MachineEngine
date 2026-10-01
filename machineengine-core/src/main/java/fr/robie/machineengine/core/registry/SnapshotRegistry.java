package fr.robie.machineengine.core.registry;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.registry.ReloadableRegistry;
import fr.robie.machineengine.api.registry.RegistryType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A reloadable registry: a volatile reference to a frozen {@link SimpleRegistry} snapshot, swapped
 * atomically (§7.3).
 */
public final class SnapshotRegistry<T extends Keyed> implements ReloadableRegistry<T> {
    private final RegistryType<T> type;
    private volatile State<T> state;

    public SnapshotRegistry(@NotNull RegistryType<T> type) {
        this.type = type;
        SimpleRegistry<T> empty = new SimpleRegistry<>(type);
        empty.freeze();
        this.state = new State<>(empty, 0);
    }

    /** Publishes a new snapshot. */
    public void swap(@NotNull SimpleRegistry<T> snapshot) {
        if (snapshot.type() != this.type) throw new IllegalArgumentException("Snapshot of " + snapshot.type() + " for " + this.type);
        snapshot.freeze();
        this.state = new State<>(snapshot, this.state.generation + 1);
    }

    public @NotNull SimpleRegistry<T> snapshot() {
        return this.state.snapshot;
    }

    @Override
    public long generation() {
        return this.state.generation;
    }

    @Override
    public @NotNull RegistryType<T> type() {
        return this.type;
    }

    @Override
    public @NotNull Optional<T> get(@NotNull Key id) {
        return this.state.snapshot.get(id);
    }

    @Override
    public boolean contains(@NotNull Key id) {
        return this.state.snapshot.contains(id);
    }

    @Override
    public boolean containsValue(@NotNull T value) {
        return this.state.snapshot.containsValue(value);
    }

    @Override
    public @NotNull Optional<AddonId> owner(@NotNull Key id) {
        return this.state.snapshot.owner(id);
    }

    @Override
    public @NotNull Set<Key> keys() {
        return this.state.snapshot.keys();
    }

    @Override
    public @NotNull List<T> values() {
        return this.state.snapshot.values();
    }

    @Override
    public String toString() {
        State<T> current = this.state;
        return "ReloadableRegistry[" + this.type + ", " + current.snapshot.size() + " entries, generation " + current.generation + "]";
    }

    private record State<T extends Keyed>(SimpleRegistry<T> snapshot, long generation) {
    }
}
