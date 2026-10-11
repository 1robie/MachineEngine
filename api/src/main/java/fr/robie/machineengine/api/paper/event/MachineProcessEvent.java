package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.process.ProcessView;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/** A process event: {@link MachineProcessStartEvent} or {@link MachineProcessCompleteEvent}. */
public abstract class MachineProcessEvent extends MachineEvent {
    private final ProcessView process;

    protected MachineProcessEvent(@NotNull MachineInstance machine, @NotNull ProcessView process) {
        super(machine);
        this.process = process;
    }

    public @NotNull ProcessView getProcess() {
        return this.process;
    }

    public @NotNull Key getRecipe() {
        return this.process.recipe();
    }
}
