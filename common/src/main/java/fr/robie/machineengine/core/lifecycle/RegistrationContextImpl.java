package fr.robie.machineengine.core.lifecycle;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.RegistrationContext;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.diagnostic.Validator;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.registry.RegistrationException;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.registry.ReloadableProvider;
import fr.robie.machineengine.api.registry.WritableRegistry;
import fr.robie.machineengine.core.registry.RegistrySet;
import fr.robie.machineengine.core.registry.SimpleRegistry;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Consumer;

/** The context one addon's registration callback runs with. Closed when the callback returns. */
final class RegistrationContextImpl implements RegistrationContext {
    private final AddonRecord record;
    private final RegistrySet registries;
    private final Consumer<Diagnostic> reports;
    private boolean open = true;

    private final EventBus events;

    RegistrationContextImpl(AddonRecord record, RegistrySet registries, Consumer<Diagnostic> reports,
                            EventBus events) {
        this.record = record;
        this.registries = registries;
        this.reports = reports;
        this.events = events;
    }

    @Override
    public @org.jetbrains.annotations.NotNull EventBus events() {
        return this.events;
    }

    @Override
    public void report(@NotNull Diagnostic diagnostic) {
        this.reports.accept(diagnostic.source() == null ? diagnostic.withSource(addon()) : diagnostic);
    }

    @Override
    public @NotNull AddonId addon() {
        return this.record.id();
    }

    @Override
    public @NotNull RegistryAccess registries() {
        return this.registries;
    }

    @Override
    public <T extends Keyed> @NotNull WritableRegistry<T> registry(@NotNull RegistryType<T> type) {
        checkOpen();
        requireExisting(type);
        if (type.isReloadable()) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.WRONG_REGISTRY_KIND,
                    type + " is reloadable; contribute to it with reloadable(...)").source(addon()).subject(type.key()).build());
        }
        return new Writable<>(this.registries.staticRegistry(type));
    }

    @Override
    public <T extends Keyed> void createRegistry(@NotNull RegistryType<T> type) {
        checkOpen();
        this.registries.create(addon(), type);
    }

    @Override
    public <T extends Keyed> void reloadable(@NotNull RegistryType<T> type, @NotNull ReloadableProvider<T> provider) {
        checkOpen();
        requireExisting(type);
        if (!type.isReloadable()) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.WRONG_REGISTRY_KIND,
                    type + " is static; register into it with registry(...)").source(addon()).subject(type.key()).build());
        }
        this.record.providers.add(new AddonRecord.ProviderEntry<>(type, Objects.requireNonNull(provider)));
    }

    @Override
    public void validator(@NotNull Validator validator) {
        checkOpen();
        this.record.validators.add(Objects.requireNonNull(validator));
    }

    void close() {
        this.open = false;
    }

    private void requireExisting(RegistryType<?> type) {
        if (!this.registries.exists(type)) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.UNKNOWN_REGISTRY,
                    "Registry " + type + " does not exist; is the addon creating it a dependency of " + addon() + "?")
                    .source(addon()).subject(type.key()).build());
        }
    }

    private void checkOpen() {
        if (!this.open) {
            throw new RegistrationException(Diagnostic.error(DiagnosticCodes.REGISTRY_FROZEN,
                    "The registration context of " + addon() + " is closed; register from inside the callback").source(addon()).build());
        }
    }

    private final class Writable<T extends Keyed> implements WritableRegistry<T> {
        private final SimpleRegistry<T> registry;

        private Writable(SimpleRegistry<T> registry) {
            this.registry = registry;
        }

        @Override
        public @NotNull RegistryType<T> type() {
            return this.registry.type();
        }

        @Override
        public <V extends T> @NotNull V register(@NotNull V value) {
            checkOpen();
            return this.registry.register(addon(), value);
        }

        @Override
        public boolean contains(@NotNull Key id) {
            return this.registry.contains(id);
        }
    }
}
