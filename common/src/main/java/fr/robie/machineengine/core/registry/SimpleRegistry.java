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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A registry filled during registration and frozen afterwards. Mutation is single-threaded;
 * reads are thread-safe once {@link #freeze() frozen}. Lookups follow {@link #aliases() aliases};
 * startup validation checks them (§24).
 */
public final class SimpleRegistry<T extends Keyed> implements Registry<T> {
    private final RegistryType<T> type;
    private Map<Key, T> entries = new LinkedHashMap<>();
    private Map<Key, AddonId> owners = new LinkedHashMap<>();
    private Map<T, Boolean> values = new IdentityHashMap<>();
    private Map<Key, Key> aliases = new LinkedHashMap<>();
    private Map<Key, AddonId> aliasOwners = new LinkedHashMap<>();
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
        AddonId aliasOwner = this.aliasOwners.get(key);
        if (aliasOwner != null) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.DUPLICATE_KEY,
                            key.asString() + " is an alias in " + this.type + " (to " + this.aliases.get(key).asString() + ", by "
                                    + aliasOwner + "); it cannot also be an entry")
                    .source(owner).subject(key).with("registry", this.type.toString()).build());
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

    /**
     * Makes {@code from} mean the entry {@code to} (see {@code WritableRegistry.alias}).
     *
     * @throws RegistrationException if frozen or {@code from} already is an alias
     */
    public void alias(@NotNull AddonId owner, @NotNull Key from, @NotNull Key to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.equals(to)) throw new IllegalArgumentException("an alias cannot point to itself: " + from.asString());
        if (this.frozen) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.REGISTRY_FROZEN,
                            "Registry " + this.type + " is frozen; the alias " + from.asString() + " cannot be added")
                    .source(owner).subject(from).build());
        }
        AddonId entryOwner = this.owners.get(from);
        if (entryOwner != null) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.DUPLICATE_KEY,
                            from.asString() + " is an entry of " + this.type + " (by " + entryOwner + "); it cannot also be an alias")
                    .source(owner).subject(from).with("registry", this.type.toString()).build());
        }
        AddonId existing = this.aliasOwners.get(from);
        if (existing != null) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.DUPLICATE_KEY,
                            from.asString() + " is already an alias in " + this.type + " (to " + this.aliases.get(from).asString()
                                    + ", by " + existing + ")")
                    .source(owner).subject(from).with("registry", this.type.toString()).build());
        }
        this.aliases.put(from, to);
        this.aliasOwners.put(from, owner);
    }

    /** Removes every entry and alias owned by one of {@code addons}. Only before freezing. */
    public void removeOwnedBy(@NotNull Set<AddonId> addons) {
        if (this.frozen) throw new IllegalStateException("Registry " + this.type + " is frozen");
        this.owners.entrySet().removeIf(entry -> {
            if (!addons.contains(entry.getValue())) return false;
            T removed = this.entries.remove(entry.getKey());
            this.values.remove(removed);
            return true;
        });
        this.aliasOwners.entrySet().removeIf(entry -> {
            if (!addons.contains(entry.getValue())) return false;
            this.aliases.remove(entry.getKey());
            return true;
        });
        this.valueList = null;
    }

    public void freeze() {
        if (this.frozen) return;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(this.entries));
        this.owners = Collections.unmodifiableMap(new LinkedHashMap<>(this.owners));
        this.values = Collections.unmodifiableMap(new IdentityHashMap<>(this.values));
        this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(this.aliases));
        this.aliasOwners = Collections.unmodifiableMap(new LinkedHashMap<>(this.aliasOwners));
        this.valueList = List.copyOf(this.entries.values());
        this.frozen = true;
    }

    @Override
    public @NotNull Optional<T> get(@NotNull Key id) {
        T entry = this.entries.get(id);
        if (entry == null) {
            Key target = this.aliases.get(id);
            if (target != null) entry = this.entries.get(target);
        }
        return Optional.ofNullable(entry);
    }

    @Override
    public boolean contains(@NotNull Key id) {
        return get(id).isPresent();
    }

    @Override
    public boolean containsValue(@NotNull T value) {
        return this.values.containsKey(value);
    }

    @Override
    public @NotNull Optional<AddonId> owner(@NotNull Key id) {
        AddonId owner = this.owners.get(id);
        return Optional.ofNullable(owner != null ? owner : this.aliasOwners.get(id));
    }

    @Override
    public @NotNull Map<Key, Key> aliases() {
        return Collections.unmodifiableMap(this.aliases);
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
