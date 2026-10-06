package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.process.ProcessStatus;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.resource.ResourceStack;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Persistent state of a running process. Outputs, costs and duration are captured when the process
 * starts, so a recipe reload never changes a process already running.
 */
record ProcessState(@NotNull Key recipe, @NotNull ProcessStatus status, long progress, long duration,
                    @NotNull List<ResourceStack> inFlight, @NotNull List<ResourceStack> outputs,
                    @NotNull List<ResourceStack> costPerTick, @NotNull ProcessBinding binding) {

    ProcessState {
        inFlight = List.copyOf(inFlight);
        outputs = List.copyOf(outputs);
        costPerTick = List.copyOf(costPerTick);
    }

    ProcessState with(ProcessStatus status, long progress) {
        return new ProcessState(this.recipe, status, progress, this.duration, this.inFlight, this.outputs, this.costPerTick, this.binding);
    }

    ProcessState withInFlight(ProcessStatus status, List<ResourceStack> inFlight) {
        return new ProcessState(this.recipe, status, this.progress, this.duration, inFlight, this.outputs, this.costPerTick, this.binding);
    }

    ProcessView view() {
        return new ProcessView(this.recipe, this.status, this.progress, this.duration, this.inFlight, this.outputs, this.costPerTick);
    }
}
