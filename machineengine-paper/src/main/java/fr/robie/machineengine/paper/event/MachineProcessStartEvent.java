package fr.robie.machineengine.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.process.ProcessView;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A machine started a process; its inputs are already consumed. */
public final class MachineProcessStartEvent extends MachineProcessEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public MachineProcessStartEvent(@NotNull MachineInstance machine, @NotNull ProcessView process) {
        super(machine, process);
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
