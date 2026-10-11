package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A machine was loaded with its chunk. */
public final class MachineLoadEvent extends MachineEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public MachineLoadEvent(@NotNull MachineInstance machine) {
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
