package fr.robie.machineengine.api.process;

import fr.robie.machineengine.api.resource.ResourceStack;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Read-only view of a running process (ARCHITECTURE.md §13).
 *
 * @param recipe      key of the recipe being processed
 * @param status      current status
 * @param progress    ticks done
 * @param duration    ticks needed (modifiers applied at start)
 * @param inFlight    inputs consumed at start and held until completion (D15)
 * @param outputs     what completion produces (captured at start)
 * @param costPerTick per-tick costs (modifiers applied at start)
 */
public record ProcessView(@NotNull Key recipe, @NotNull ProcessStatus status, long progress, long duration,
                          @NotNull List<ResourceStack> inFlight, @NotNull List<ResourceStack> outputs,
                          @NotNull List<ResourceStack> costPerTick) {

    public ProcessView {
        inFlight = List.copyOf(inFlight);
        outputs = List.copyOf(outputs);
        costPerTick = List.copyOf(costPerTick);
    }

    public long remaining() {
        return Math.max(0, this.duration - this.progress);
    }
}
