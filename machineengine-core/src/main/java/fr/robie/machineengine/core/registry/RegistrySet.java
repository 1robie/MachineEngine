package fr.robie.machineengine.core.registry;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.registry.RegistrationException;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** All registries of an engine: static ones and reloadable ones, with the addon that created each. */
public final class RegistrySet implements RegistryAccess {
    private final Map<Key, Registry<?>> registries = new LinkedHashMap<>();
    private final Map<Key, RegistryType<?>> types = new LinkedHashMap<>();
    private final Map<Key, AddonId> creators = new LinkedHashMap<>();
    private boolean frozen;

    /**
     * @throws RegistrationException if a registry with this key exists
     */
    public <T extends Keyed> void create(@NotNull AddonId creator, @NotNull RegistryType<T> type) {
        if (this.frozen) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.REGISTRY_FROZEN,
                    "Registries are frozen; " + type + " cannot be created").source(creator).subject(type.key()).build());
        }
        AddonId existing = this.creators.get(type.key());
        if (existing != null) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.DUPLICATE_KEY,
                    "Registry " + type + " was already created by " + existing).source(creator).subject(type.key()).build());
        }
        Registry<T> registry = type.isReloadable() ? new SnapshotRegistry<>(type) : new SimpleRegistry<>(type);
        this.registries.put(type.key(), registry);
        this.types.put(type.key(), type);
        this.creators.put(type.key(), creator);
    }

    /** Removes the registries created by {@code addons} and every entry they own elsewhere. */
    public void removeOwnedBy(@NotNull Set<AddonId> addons) {
        if (addons.isEmpty()) return;
        List<Key> removed = new ArrayList<>();
        this.creators.forEach((key, creator) -> {
            if (addons.contains(creator)) removed.add(key);
        });
        removed.forEach(key -> {
            this.registries.remove(key);
            this.types.remove(key);
            this.creators.remove(key);
        });
        for (Registry<?> registry : this.registries.values()) {
            if (registry instanceof SimpleRegistry<?> simple) simple.removeOwnedBy(addons);
        }
    }

    public void freezeStatic() {
        for (Registry<?> registry : this.registries.values()) {
            if (registry instanceof SimpleRegistry<?> simple) simple.freeze();
        }
        this.frozen = true;
    }

    @SuppressWarnings("unchecked")
    public <T extends Keyed> @NotNull SimpleRegistry<T> staticRegistry(@NotNull RegistryType<T> type) {
        if (registry(type) instanceof SimpleRegistry<?> simple) return (SimpleRegistry<T>) simple;
        throw new IllegalArgumentException(type + " is not a static registry");
    }

    @SuppressWarnings("unchecked")
    public <T extends Keyed> @NotNull SnapshotRegistry<T> reloadableRegistry(@NotNull RegistryType<T> type) {
        if (registry(type) instanceof SnapshotRegistry<?> snapshot) return (SnapshotRegistry<T>) snapshot;
        throw new IllegalArgumentException(type + " is not a reloadable registry");
    }

    public boolean exists(@NotNull RegistryType<?> type) {
        return this.types.get(type.key()) != null;
    }

    public @NotNull Optional<AddonId> creator(@NotNull RegistryType<?> type) {
        return Optional.ofNullable(this.creators.get(type.key()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> @NotNull Registry<T> registry(@NotNull RegistryType<T> type) {
        RegistryType<?> known = this.types.get(type.key());
        if (known == null) throw new IllegalArgumentException("No registry " + type);
        if (!known.equals(type)) throw new IllegalArgumentException("Registry " + type + " exists with a different definition: " + known);
        return (Registry<T>) this.registries.get(type.key());
    }

    @Override
    public @NotNull Optional<Registry<?>> find(@NotNull Key registryKey) {
        return Optional.ofNullable(this.registries.get(registryKey));
    }

    @Override
    public @NotNull Collection<RegistryType<?>> types() {
        return Collections.unmodifiableCollection(this.types.values());
    }

    /**
     * @return a view where {@code type} reads from {@code snapshot} instead (used to validate a
     *         snapshot before it is published)
     */
    public <T extends Keyed> @NotNull RegistryAccess withSnapshot(@NotNull RegistryType<T> type, @NotNull Registry<T> snapshot) {
        RegistrySet self = this;
        return new RegistryAccess() {
            @Override
            @SuppressWarnings("unchecked")
            public <U extends Keyed> @NotNull Registry<U> registry(@NotNull RegistryType<U> requested) {
                return requested.equals(type) ? (Registry<U>) snapshot : self.registry(requested);
            }

            @Override
            public @NotNull Optional<Registry<?>> find(@NotNull Key registryKey) {
                return registryKey.equals(type.key()) ? Optional.of(snapshot) : self.find(registryKey);
            }

            @Override
            public @NotNull Collection<RegistryType<?>> types() {
                return self.types();
            }
        };
    }
}
