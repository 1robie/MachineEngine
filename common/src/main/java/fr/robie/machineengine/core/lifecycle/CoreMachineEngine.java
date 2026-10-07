package fr.robie.machineengine.core.lifecycle;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.definition.BuiltInBehaviors;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.event.EngineEvents;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.lifecycle.LifecyclePhase;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.registry.RegistrationException;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.registry.ReloadContext;
import fr.robie.machineengine.api.resource.BuiltInResources;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.convert.ResourceConverterLookup;
import fr.robie.machineengine.api.resource.tag.ResourceTagContribution;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.upgrade.ModifierType;
import fr.robie.machineengine.api.upgrade.StandardModifiers;
import fr.robie.machineengine.api.version.Version;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.event.CoreEventBus;
import fr.robie.machineengine.core.machine.CoreMachines;
import fr.robie.machineengine.core.registry.RegistrySet;
import fr.robie.machineengine.core.registry.SimpleRegistry;
import fr.robie.machineengine.core.resource.BuiltInValidators;
import fr.robie.machineengine.core.resource.CoreConverterLookup;
import fr.robie.machineengine.core.resource.ItemTagSource;
import fr.robie.machineengine.core.resource.ResourceCodecs;
import fr.robie.machineengine.core.resource.TagSnapshot;
import fr.robie.machineengine.core.transfer.CoreTransactionManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The engine: runs the startup phases of §8.2 and then serves the runtime API. */
public final class CoreMachineEngine implements MachineEngine {
    private final Logger logger;
    private final RegistrySet registries = new RegistrySet();
    private final Map<AddonId, AddonRecord> enabled = new LinkedHashMap<>();
    private final Set<AddonId> submitted = new TreeSet<>();
    private final CoreEventBus events;
    private final CoreTransactionManager transactions;
    private final Set<Diagnostic> startupDiagnostics = new LinkedHashSet<>();
    private volatile LifecyclePhase phase = LifecyclePhase.BOOTSTRAP;
    private volatile ResourceTags tags = ResourceTags.EMPTY;
    private ItemTagSource itemTagSource = ItemTagSource.NONE;
    private ResourceConverterLookup converters;
    private ResourceCodecs resourceCodecs;
    private CoreMachines machines;
    private volatile Function<WorldPosition,
            MachineInstance> machineLookup = position -> null;

    private CoreMachineEngine(Logger logger) {
        this.logger = logger;
        this.events = new CoreEventBus(logger);
        this.transactions = new CoreTransactionManager(logger);
    }

    static CoreMachineEngine start(List<AddonRegistration> registrations, Version apiVersion, Logger logger) {
        return start(registrations, apiVersion, logger, ItemTagSource.NONE);
    }

    static CoreMachineEngine start(List<AddonRegistration> registrations, Version apiVersion, Logger logger,
                                   ItemTagSource itemTags) {
        CoreMachineEngine engine = new CoreMachineEngine(logger);
        engine.itemTagSource = itemTags;
        engine.createBuiltInRegistries();
        engine.register(registrations, apiVersion);
        engine.validate();
        engine.freeze();
        engine.loadReloadables();
        engine.phase = LifecyclePhase.RUNTIME;
        engine.events.post(EngineEvents.REGISTRIES_FROZEN, EventPhase.POST,
                new EngineEvents.Frozen(engine.registries));
        ValidationReport report = engine.startupReport();
        if (report.hasErrors()) {
            logger.warning("MachineEngine started with errors; disabled addons: " + engine.disabledAddons()
                    + System.lineSeparator() + report.format());
        }
        return engine;
    }

    // ------------------------------------------------------------------------------------------
    // Startup phases
    // ------------------------------------------------------------------------------------------

    private void createBuiltInRegistries() {
        this.phase = LifecyclePhase.REGISTRY_CREATION;
        for (RegistryType<?> type : List.of(Registries.RESOURCE_CATEGORY, Registries.RESOURCE_TYPE,
                Registries.RESOURCE_COMPONENT_TYPE, Registries.RESOURCE_CONVERTER, Registries.STORAGE_CODEC,
                Registries.EVENT_TYPE, Registries.CAPABILITY_TYPE, Registries.MACHINE_TYPE, Registries.RECIPE_TYPE,
                Registries.MODIFIER_TYPE, Registries.UPGRADE_TYPE, Registries.NETWORK_TYPE, Registries.TRANSPORT_TYPE,
                Registries.MULTIBLOCK_TYPE, Registries.BEHAVIOR_TYPE,
                Registries.RECIPE, Registries.RESOURCE_TAG, Registries.ITEM_TAG)) {
            this.registries.create(AddonId.MACHINE_ENGINE, type);
        }
        // Infrastructure MachineEngine itself provides (never meanings, §4).
        this.registries.staticRegistry(Registries.CAPABILITY_TYPE).register(AddonId.MACHINE_ENGINE, Capabilities.RESOURCE_HANDLER);
        this.registries.staticRegistry(Registries.BEHAVIOR_TYPE).register(AddonId.MACHINE_ENGINE,
                BuiltInBehaviors.PROCESSING);
        this.registries.staticRegistry(Registries.BEHAVIOR_TYPE).register(AddonId.MACHINE_ENGINE,
                BuiltInBehaviors.STORAGE);
        this.registries.staticRegistry(Registries.BEHAVIOR_TYPE).register(AddonId.MACHINE_ENGINE,
                BuiltInBehaviors.MULTIBLOCK_MEMBER);
        // Minecraft items (one resource type; the concrete item is a component, §23.4).
        this.registries.staticRegistry(Registries.RESOURCE_CATEGORY).register(AddonId.MACHINE_ENGINE, Items.CATEGORY);
        this.registries.staticRegistry(Registries.RESOURCE_COMPONENT_TYPE).register(AddonId.MACHINE_ENGINE, Items.DATA);
        this.registries.staticRegistry(Registries.RESOURCE_TYPE).register(AddonId.MACHINE_ENGINE, Items.ITEM);
        // Energy and fluids every addon shares (D29), with the units of config.yml.
        BuiltInResources.Built resources = BuiltInResources.get();
        this.registries.staticRegistry(Registries.RESOURCE_CATEGORY).register(AddonId.MACHINE_ENGINE, resources.energyCategory());
        this.registries.staticRegistry(Registries.RESOURCE_TYPE).register(AddonId.MACHINE_ENGINE, resources.energy());
        this.registries.staticRegistry(Registries.RESOURCE_CATEGORY).register(AddonId.MACHINE_ENGINE, resources.fluidCategory());
        this.registries.staticRegistry(Registries.RESOURCE_TYPE).register(AddonId.MACHINE_ENGINE, resources.water());
        this.registries.staticRegistry(Registries.RESOURCE_TYPE).register(AddonId.MACHINE_ENGINE, resources.lava());
        for (ModifierType modifier : List.of(StandardModifiers.PROCESS_DURATION, StandardModifiers.PROCESS_COST,
                StandardModifiers.TRANSFER_RATE, StandardModifiers.PROCESSES_PER_TICK, StandardModifiers.processCost(resources.energyCategory()))) {
            this.registries.staticRegistry(Registries.MODIFIER_TYPE).register(AddonId.MACHINE_ENGINE, modifier);
        }
        for (EventType<?> event : List.of(MachineEvents.MACHINE_CREATE, MachineEvents.MACHINE_LOAD, MachineEvents.MACHINE_UNLOAD,
                MachineEvents.MACHINE_REMOVE, MachineEvents.PROCESS_START, MachineEvents.PROCESS_COMPLETE,
                MachineEvents.UPGRADE_CHANGE, MachineEvents.RESOURCES_CHANGED,
                EngineEvents.REGISTRIES_FROZEN, EngineEvents.REGISTRY_RELOADED)) {
            this.registries.staticRegistry(Registries.EVENT_TYPE).register(AddonId.MACHINE_ENGINE, event);
        }
    }

    private void register(List<AddonRegistration> registrations, Version apiVersion) {
        this.phase = LifecyclePhase.DEPENDENCY_RESOLUTION;
        registrations.forEach(registration -> this.submitted.add(registration.addon()));
        AddonResolver.Resolution resolution = AddonResolver.resolve(registrations, apiVersion);
        this.startupDiagnostics.addAll(resolution.diagnostics());

        this.phase = LifecyclePhase.REGISTRATION;
        Set<AddonId> disabled = new HashSet<>(this.submitted);
        resolution.ordered().forEach(registration -> disabled.remove(registration.addon()));
        for (AddonRegistration registration : resolution.ordered()) {
            AddonRecord record = new AddonRecord(registration);
            if (record.requiresAny(disabled)) {
                disabled.add(record.id());
                this.startupDiagnostics.add(Diagnostic.error(DiagnosticCodes.DEPENDENCY_DISABLED,
                        record.id() + " requires an addon that failed to register").source(record.id()).build());
                continue;
            }
            if (runCallback(record)) {
                this.enabled.put(record.id(), record);
            } else {
                disabled.add(record.id());
                this.registries.removeOwnedBy(Set.of(record.id()));
            }
        }
    }

    private boolean runCallback(AddonRecord record) {
        RegistrationContextImpl context = new RegistrationContextImpl(record, this.registries, this.startupDiagnostics::add, this.events);
        try {
            record.registration.callback().accept(context);
            return true;
        } catch (RegistrationException e) {
            Diagnostic diagnostic = e.diagnostic();
            this.startupDiagnostics.add(diagnostic.source() == null ? diagnostic.withSource(record.id()) : diagnostic);
            return false;
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Registration of " + record.id() + " failed", e);
            this.startupDiagnostics.add(Diagnostic.error(DiagnosticCodes.REGISTRATION_FAILED,
                            "Registration callback threw " + e.getClass().getSimpleName() + ": " + e.getMessage())
                    .source(record.id()).build());
            return false;
        } finally {
            context.close();
        }
    }

    /**
     * Validates everything, disables the addons responsible for errors (and the addons requiring
     * them), and repeats until a pass disables nothing (§8.2 step 4).
     */
    private void validate() {
        this.phase = LifecyclePhase.VALIDATION;
        while (true) {
            List<Diagnostic> round = ValidationRunner.run(this.registries, staticValidators(), this.logger);
            Set<AddonId> failed = new TreeSet<>();
            for (Diagnostic diagnostic : round) {
                if (diagnostic.isError() && diagnostic.source() != null && this.enabled.containsKey(diagnostic.source())) {
                    failed.add(diagnostic.source());
                }
            }
            if (failed.isEmpty()) {
                this.startupDiagnostics.addAll(round);
                return;
            }
            round.stream().filter(Diagnostic::isError).forEach(this.startupDiagnostics::add);
            disableWithDependents(failed);
        }
    }

    private void disableWithDependents(Set<AddonId> failed) {
        Set<AddonId> disabling = new TreeSet<>(failed);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (AddonRecord record : this.enabled.values()) {
                if (!disabling.contains(record.id()) && record.requiresAny(disabling)) {
                    disabling.add(record.id());
                    this.startupDiagnostics.add(Diagnostic.error(DiagnosticCodes.DEPENDENCY_DISABLED,
                            record.id() + " requires an addon whose content was disabled").source(record.id()).build());
                    changed = true;
                }
            }
        }
        disabling.forEach(this.enabled::remove);
        this.registries.removeOwnedBy(disabling);
    }

    private List<ValidationRunner.OwnedValidator> staticValidators() {
        List<ValidationRunner.OwnedValidator> validators = new ArrayList<>();
        BuiltInValidators.staticValidators().forEach(v -> validators.add(new ValidationRunner.OwnedValidator(AddonId.MACHINE_ENGINE, v)));
        this.enabled.values().forEach(record -> record.validators.forEach(v -> validators.add(new ValidationRunner.OwnedValidator(record.id(), v))));
        return validators;
    }

    private void freeze() {
        this.registries.freezeStatic();
        this.converters = new CoreConverterLookup(this.registries.registry(Registries.RESOURCE_CONVERTER));
        this.resourceCodecs = new ResourceCodecs(this.registries);
        this.machines = new CoreMachines(this.registries, this.transactions, this::tags, this.events, this.resourceCodecs, this.logger);
        this.phase = LifecyclePhase.FROZEN;
    }

    /**
     * Builds the first snapshot of every reloadable registry. Providers whose addon caused errors
     * are left out (there is no previous snapshot to keep).
     */
    private void loadReloadables() {
        for (RegistryType<?> type : List.copyOf(this.registries.types())) {
            if (type.isReloadable()) loadInitial(type);
        }
    }

    private <T extends Keyed> void loadInitial(RegistryType<T> type) {
        Set<AddonId> excluded = new HashSet<>();
        while (true) {
            SnapshotBuild<T> build = buildSnapshot(type, excluded);
            Set<AddonId> failed = new TreeSet<>();
            for (Diagnostic diagnostic : build.diagnostics) {
                AddonId source = diagnostic.source();
                if (diagnostic.isError() && source != null && !excluded.contains(source) && providesTo(source, type)) {
                    failed.add(source);
                }
            }
            if (failed.isEmpty()) {
                this.startupDiagnostics.addAll(build.diagnostics);
                publish(type, build.snapshot);
                return;
            }
            build.diagnostics.stream().filter(Diagnostic::isError).forEach(this.startupDiagnostics::add);
            excluded.addAll(failed);
        }
    }

    private boolean providesTo(AddonId addon, RegistryType<?> type) {
        AddonRecord record = this.enabled.get(addon);
        return record != null && record.providers.stream().anyMatch(entry -> entry.type().equals(type));
    }

    private record SnapshotBuild<T extends Keyed>(SimpleRegistry<T> snapshot, List<Diagnostic> diagnostics) {
    }

    @SuppressWarnings("unchecked")
    private <T extends Keyed> SnapshotBuild<T> buildSnapshot(RegistryType<T> type, Set<AddonId> excluded) {
        SimpleRegistry<T> snapshot = new SimpleRegistry<>(type);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (AddonRecord record : this.enabled.values()) {
            if (excluded.contains(record.id())) continue;
            for (AddonRecord.ProviderEntry<?> entry : record.providers) {
                if (!entry.type().equals(type)) continue;
                runProvider(record.id(), snapshot, (AddonRecord.ProviderEntry<T>) entry, diagnostics);
            }
        }
        List<ValidationRunner.OwnedValidator> validators = new ArrayList<>();
        BuiltInValidators.reloadValidators(type).forEach(v -> validators.add(new ValidationRunner.OwnedValidator(AddonId.MACHINE_ENGINE, v)));
        this.enabled.values().forEach(record -> {
            if (!excluded.contains(record.id())) {
                record.validators.forEach(v -> validators.add(new ValidationRunner.OwnedValidator(record.id(), v)));
            }
        });
        diagnostics.addAll(ValidationRunner.run(this.registries.withSnapshot(type, snapshot), validators, this.logger));
        return new SnapshotBuild<>(snapshot, diagnostics);
    }

    /** Runs one provider; its entries are added only if it completes without error. */
    private <T extends Keyed> void runProvider(AddonId addon, SimpleRegistry<T> snapshot, AddonRecord.ProviderEntry<T> entry,
                                               List<Diagnostic> diagnostics) {
        Map<Key, T> staged = new LinkedHashMap<>();
        ReloadContext<T> context = new ReloadContext<>() {
            @Override
            public @NotNull AddonId addon() {
                return addon;
            }

            @Override
            public @NotNull RegistryAccess registries() {
                return CoreMachineEngine.this.registries;
            }

            @Override
            public void report(@NotNull Diagnostic diagnostic) {
                diagnostics.add(diagnostic.source() == null ? diagnostic.withSource(addon) : diagnostic);
            }

            @Override
            public <V extends T> @NotNull V register(@NotNull V value) {
                Key key = value.key();
                if (staged.containsKey(key) || snapshot.contains(key)) {
                    String first = snapshot.owner(key).map(AddonId::name).orElse(addon.name());
                    throw new RegistrationException(Diagnostic.error(DiagnosticCodes.DUPLICATE_KEY,
                                    key.asString() + " is already registered in " + entry.type() + " by " + first)
                            .source(addon).subject(key).build());
                }
                if (!entry.type().valueType().isInstance(value)) {
                    throw new RegistrationException(Diagnostic.error(DiagnosticCodes.WRONG_VALUE_TYPE,
                            key.asString() + " is not a " + entry.type().valueType().getName()).source(addon).subject(key).build());
                }
                staged.put(key, value);
                return value;
            }
        };
        try {
            entry.provider().provide(context);
        } catch (RegistrationException e) {
            diagnostics.add(e.diagnostic().source() == null ? e.diagnostic().withSource(addon) : e.diagnostic());
            return;
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "A " + entry.type() + " provider of " + addon + " failed", e);
            diagnostics.add(Diagnostic.error(DiagnosticCodes.RELOAD_FAILED,
                            "A " + entry.type() + " provider threw " + e.getClass().getSimpleName() + ": " + e.getMessage())
                    .source(addon).build());
            return;
        }
        staged.values().forEach(value -> snapshot.register(addon, value));
    }

    private <T extends Keyed> void publish(RegistryType<T> type, SimpleRegistry<T> snapshot) {
        this.registries.reloadableRegistry(type).swap(snapshot);
        if (type.equals(Registries.RESOURCE_TAG) || type.equals(Registries.ITEM_TAG)) rebuildTags();
    }

    private void rebuildTags() {
        Map<Key, Set<ResourceType>> tags = new LinkedHashMap<>();
        for (ResourceTagContribution contribution : this.registries.registry(Registries.RESOURCE_TAG)) {
            tags.computeIfAbsent(contribution.tag(), tag -> new LinkedHashSet<>()).addAll(contribution.members());
        }
        tags.replaceAll((tag, members) -> Collections.unmodifiableSet(members));
        this.tags = new TagSnapshot(tags,
                this.registries.exists(Registries.ITEM_TAG) ? this.registries.registry(Registries.ITEM_TAG) : List.of(),
                this.itemTagSource);
    }

    // ------------------------------------------------------------------------------------------
    // Runtime API
    // ------------------------------------------------------------------------------------------

    @Override
    public @NotNull LifecyclePhase phase() {
        return this.phase;
    }

    @Override
    public @NotNull RegistryAccess registries() {
        return this.registries;
    }

    @Override
    public @NotNull EventBus events() {
        return this.events;
    }

    @Override
    public @NotNull TransactionManager transactions() {
        return this.transactions;
    }

    @Override
    public @NotNull ResourceConverterLookup converters() {
        return this.converters;
    }

    @Override
    public @NotNull ResourceTags tags() {
        return this.tags;
    }

    public @NotNull ResourceCodecs resourceCodecs() {
        return this.resourceCodecs;
    }

    @Override
    public @NotNull Optional<MachineInstance> machineAt(
            @NotNull WorldPosition position) {
        return Optional.ofNullable(this.machineLookup.apply(position));
    }

    /** Lets the platform answer {@link #machineAt}. */
    public void setMachineLookup(@NotNull Function<WorldPosition,
            MachineInstance> lookup) {
        this.machineLookup = lookup;
    }

    /** Machine placement, loading and persistence, for hosts. */
    public @NotNull CoreMachines machines() {
        return this.machines;
    }

    @Override
    public @NotNull Set<AddonId> enabledAddons() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.enabled.keySet()));
    }

    @Override
    public @NotNull Set<AddonId> disabledAddons() {
        Set<AddonId> disabled = new TreeSet<>(this.submitted);
        disabled.removeAll(this.enabled.keySet());
        return Collections.unmodifiableSet(disabled);
    }

    @Override
    public @NotNull ValidationReport startupReport() {
        return ValidationReport.of(this.startupDiagnostics);
    }

    @Override
    public synchronized @NotNull ValidationReport reload(@NotNull RegistryType<?> type) {
        if (!type.isReloadable() || !this.registries.exists(type)) {
            throw new IllegalArgumentException(type + " is not a reloadable registry");
        }
        return reloadTyped(type);
    }

    private <T extends Keyed> ValidationReport reloadTyped(RegistryType<T> type) {
        SnapshotBuild<T> build = buildSnapshot(type, Set.of());
        ValidationReport report = ValidationReport.of(build.diagnostics);
        if (report.hasErrors()) {
            this.logger.warning("Reload of " + type + " rejected; the previous snapshot stays live" + System.lineSeparator() + report.format());
            ValidationReport rejected = report.merge(new ValidationReport(List.of(Diagnostic.error(DiagnosticCodes.RELOAD_FAILED,
                    "Reload of " + type + " was rejected; the previous snapshot stays live").subject(type.key()).build())));
            this.events.post(EngineEvents.REGISTRY_RELOADED, EventPhase.POST,
                    new EngineEvents.Reloaded(type, false, rejected));
            return rejected;
        }
        publish(type, build.snapshot);
        this.events.post(EngineEvents.REGISTRY_RELOADED, EventPhase.POST,
                new EngineEvents.Reloaded(type, true, report));
        return report;
    }
}
