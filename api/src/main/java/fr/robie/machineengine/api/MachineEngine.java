package fr.robie.machineengine.api;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.lifecycle.LifecyclePhase;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.resource.convert.ResourceConverterLookup;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.world.WorldPosition;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;

/** A running MachineEngine: frozen registries and the runtime services built on them. */
@ApiStatus.NonExtendable
public interface MachineEngine {

    /**
     * Submits an addon's registration. Call it from the plugin's {@code onLoad()}; MachineEngine runs
     * all registrations in dependency order when it enables, before worlds load (D13).
     *
     * <pre>{@code
     * MachineEngine.register(AddonRegistration.builder(getName(), getPluginMeta().getVersion())
     *         .requiresApi("[0.1,1.0)")
     *         .register(ctx -> ctx.machineTypes().register(MY_MACHINE))
     *         .build());
     * }</pre>
     *
     * @throws IllegalStateException if registration is already closed
     */
    static void register(@NotNull AddonRegistration registration) {
        MachineEngineAccess.submit(registration);
    }

    /**
     * @return the running engine
     * @throws IllegalStateException before MachineEngine has enabled
     */
    static @NotNull MachineEngine get() {
        return MachineEngineAccess.engine();
    }

    /**
     * MachineEngine's settings ({@link EngineConfig}), loaded before addons' {@code onLoad}. Without a
     * server (headless tests) every key has its default. A reload swaps the values; read them when
     * needed instead of keeping them.
     */
    static @NotNull ConfigValues config() {
        return MachineEngineAccess.config();
    }

    @NotNull LifecyclePhase phase();

    @NotNull RegistryAccess registries();

    @NotNull EventBus events();

    @NotNull TransactionManager transactions();

    @NotNull ResourceConverterLookup converters();

    /** The current tag snapshot. */
    @NotNull ResourceTags tags();

    /** Addons whose content is active. */
    @NotNull Set<AddonId> enabledAddons();

    /** Addons whose content was disabled at startup because of errors (see {@link #startupReport()}). */
    @NotNull Set<AddonId> disabledAddons();

    /** Everything found while starting. */
    @NotNull ValidationReport startupReport();

    /**
     * Rebuilds a reloadable registry. If the new snapshot has errors, it is rejected and the current
     * one stays live (§7.3).
     *
     * @return the findings of the reload
     * @throws IllegalArgumentException if {@code type} is not a reloadable registry
     */
    @NotNull ValidationReport reload(@NotNull RegistryType<?> type);

    /**
     * The loaded machine at {@code position}. Owner thread of that position only. Empty if there is
     * none, if it is a placeholder, or if no platform hosts machines (headless).
     */
    @NotNull Optional<MachineInstance> machineAt(
            @NotNull WorldPosition position);
}
