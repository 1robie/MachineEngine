package fr.robie.machineengine.paper.event;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.event.EngineEvents;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.machine.MachineEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * Mirrors MachineEngine's machine events as Bukkit events (§26), for plugins that do not use the
 * MachineEngine API. A Bukkit event is only built when a Bukkit listener is registered for it.
 */
public final class BukkitEventBridge {
    private BukkitEventBridge() {
    }

    public static void install(@NotNull EventBus events) {
        mirror(events, MachineEvents.MACHINE_CREATE, MachineCreateEvent.getHandlerList(), e -> new MachineCreateEvent(e.machine()));
        mirror(events, MachineEvents.MACHINE_LOAD, MachineLoadEvent.getHandlerList(), e -> new MachineLoadEvent(e.machine()));
        mirror(events, MachineEvents.MACHINE_UNLOAD, MachineUnloadEvent.getHandlerList(), e -> new MachineUnloadEvent(e.machine()));
        mirror(events, MachineEvents.MACHINE_REMOVE, MachineRemoveEvent.getHandlerList(), e -> new MachineRemoveEvent(e.machine()));
        mirror(events, MachineEvents.PROCESS_START, MachineProcessStartEvent.getHandlerList(),
                e -> new MachineProcessStartEvent(e.machine(), e.process()));
        mirror(events, MachineEvents.PROCESS_COMPLETE, MachineProcessCompleteEvent.getHandlerList(),
                e -> new MachineProcessCompleteEvent(e.machine(), e.process()));
        mirror(events, MachineEvents.UPGRADE_CHANGE, MachineUpgradeChangeEvent.getHandlerList(),
                e -> new MachineUpgradeChangeEvent(e.machine(), e.upgrade(), e.previous(), e.current()));
        mirror(events, EngineEvents.REGISTRY_RELOADED, MachineEngineReloadEvent.getHandlerList(),
                e -> new MachineEngineReloadEvent(e.registry(), e.accepted(), e.report()));
        // RESOURCES_CHANGED is not mirrored: subscribing here would make every machine sum its
        // transfers even when no plugin listens. Use the MachineEngine event bus for it.
    }

    private static <E> void mirror(EventBus events, EventType<E> type, HandlerList handlers, Function<E, Event> bukkit) {
        events.subscribe(AddonId.MACHINE_ENGINE, type, EventPhase.POST, event -> {
            if (handlers.getRegisteredListeners().length > 0) Bukkit.getPluginManager().callEvent(bukkit.apply(event));
        });
    }
}
