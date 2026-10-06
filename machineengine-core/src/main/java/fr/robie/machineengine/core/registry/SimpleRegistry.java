package fr.robie.machineengine.core.registry;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.registry.RegistrationException;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.registry.RegistryType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A registry filled during registration and frozen afterwards. Mutation is single-threaded;
 * reads are thread-safe once {@link #freeze() frozen}.
 */
public final class SimpleRegistry<T extends Keyed> implements Registry<T> {
    private final RegistryType<T> type;
    private Map<Key, T> entries = new LinkedHashMap<>();
    private Map<Key, AddonId> owners = new LinkedHashMap<>();
    private Map<T, Boolean> values = new IdentityHashMap<>();
    private List<T> valueList;
    private boolean frozen;

    public SimpleRegistry(@NotNull RegistryType<T> type) {
        this.type = type;
    }

    @Override
    public @NotNull RegistryType<T> type() {
        return this.type;
    }

    /**
     * @throws RegistrationException if frozen, the key is taken, or the value has the wrong type
     */
    public <V extends T> @NotNull V register(@NotNull AddonId owner, @NotNull V value) {
        Key key = value.key();
        if (this.frozen) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.REGISTRY_FROZEN,
                            "Registry " + this.type + " is frozen; " + key.asString() + " cannot be registered")
                    .source(owner).subject(key).build());
        }
        if (!this.type.valueType().isInstance(value)) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.WRONG_VALUE_TYPE,
                            key.asString() + " is a " + value.getClass().getName() + ", but registry " + this.type
                                    + " holds " + this.type.valueType().getName())
                    .source(owner).subject(key).build());
        }
        AddonId existing = this.owners.get(key);
        if (existing != null) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.DUPLICATE_KEY,
                            key.asString() + " is already registered in " + this.type + " by " + existing)
                    .source(owner).subject(key).with("registry", this.type.toString())
                    .with("first", existing.name()).with("second", owner.name()).build());
        }
        this.entries.put(key, value);
        this.owners.put(key, owner);
        this.values.put(value, Boolean.TRUE);
        this.valueList = null;
        return value;
    }

    /** Removes every entry owned by one of {@code addons}. Only before freezing. */
    public void removeOwnedBy(@NotNull Set<AddonId> addons) {
        if (this.frozen) throw new IllegalStateException("Registry " + this.type + " is frozen");
        this.owners.entrySet().removeIf(entry -> {
            if (!addons.contains(entry.getValue())) return false;
            T removed = this.entries.remove(entry.getKey());
            this.values.remove(removed);
            return true;
        });
        this.valueList = null;
    }

    public void freeze() {
        if (this.frozen) return;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(this.entries));
        this.owners = Collections.unmodifiableMap(new LinkedHashMap<>(this.owners));
        this.values = Collections.unmodifiableMap(new IdentityHashMap<>(this.values));
        this.valueList = List.copyOf(this.entries.values());
        this.frozen = true;
    }

    @Override
    public @NotNull Optional<T> get(@NotNull Key id) {
        return Optional.ofNullable(this.entries.get(id));
    }

    @Override
    public boolean contains(@NotNull Key id) {
        return this.entries.containsKey(id);
    }

    @Override
    public boolean containsValue(@NotNull T value) {
        return this.values.containsKey(value);
    }

    @Override
    public @NotNull Optional<AddonId> owner(@NotNull Key id) {
        return Optional.ofNullable(this.owners.get(id));
    }

    @Override
    public @NotNull Set<Key> keys() {
        return Collections.unmodifiableSet(this.entries.keySet());
    }

    @Override
    public @NotNull List<T> values() {
        List<T> list = this.valueList;
        if (list == null) {
            list = List.copyOf(this.entries.values());
            this.valueList = list;
        }
        return list;
    }

    @Override
    public int size() {
        return this.entries.size();
    }

    @Override
    public String toString() {
        return "Registry[" + this.type + ", " + this.entries.size() + " entries" + (this.frozen ? ", frozen" : "") + "]";
    }
}
