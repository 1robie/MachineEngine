package fr.robie.machineengine.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A machine was removed (broken). */
public final class MachineRemoveEvent extends MachineEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public MachineRemoveEvent(@NotNull MachineInstance machine) {
        super(machine);
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
