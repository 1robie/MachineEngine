package fr.robie.machineengine.api;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.lifecycle.LifecyclePhase;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.resource.convert.ResourceConverterLookup;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.TransactionManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/** A running MachineEngine: frozen registries and the runtime services built on them. */
@ApiStatus.NonExtendable
public interface MachineEngine {

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
}
