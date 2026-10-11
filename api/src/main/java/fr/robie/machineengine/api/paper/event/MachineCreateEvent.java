package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A machine was created (placed). */
public final class MachineCreateEvent extends MachineEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public MachineCreateEvent(@NotNull MachineInstance machine) {
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
