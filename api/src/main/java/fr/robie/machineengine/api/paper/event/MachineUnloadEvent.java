package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A machine is unloading with its chunk. */
public final class MachineUnloadEvent extends MachineEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public MachineUnloadEvent(@NotNull MachineInstance machine) {
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
