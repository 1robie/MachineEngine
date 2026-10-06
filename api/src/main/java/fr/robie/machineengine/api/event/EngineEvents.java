package fr.robie.machineengine.api.event;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import org.jetbrains.annotations.NotNull;

/**
 * Engine lifecycle events (ARCHITECTURE.md §26), posted in the {@link EventPhase#POST} phase.
 * Subscribe from {@code RegistrationContext.events()} to receive {@link #REGISTRIES_FROZEN}.
 */
public final class EngineEvents {
    /** Static registries are frozen and the first reloadable snapshots are live; machines can now be loaded. */
    public static final EventType<Frozen> REGISTRIES_FROZEN = EventType.of(MachineEngineKeys.key("registries_frozen"), Frozen.class);
    /** A reloadable registry (recipes, tags …) was reloaded, or the reload was rejected. */
    public static final EventType<Reloaded> REGISTRY_RELOADED = EventType.of(MachineEngineKeys.key("registry_reloaded"), Reloaded.class);

    private EngineEvents() {
    }

    public record Frozen(@NotNull RegistryAccess registries) {
    }

    /**
     * @param accepted whether the new snapshot is live; if not, the previous one stays and
     *                 {@code report} says why
     */
    public record Reloaded(@NotNull RegistryType<?> registry, boolean accepted, @NotNull ValidationReport report) {
    }
}
