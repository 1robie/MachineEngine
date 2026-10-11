package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.registry.RegistryType;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A reloadable registry (recipes, tags …) was reloaded, or its reload was rejected. */
public final class MachineEngineReloadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final RegistryType<?> registry;
    private final boolean accepted;
    private final ValidationReport report;

    public MachineEngineReloadEvent(@NotNull RegistryType<?> registry, boolean accepted, @NotNull ValidationReport report) {
        super(!Bukkit.isPrimaryThread());
        this.registry = registry;
        this.accepted = accepted;
        this.report = report;
    }

    public @NotNull RegistryType<?> getRegistry() {
        return this.registry;
    }

    /** Whether the new content is live; if not, the previous content stays and the report says why. */
    public boolean isAccepted() {
        return this.accepted;
    }

    public @NotNull ValidationReport getReport() {
        return this.report;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
