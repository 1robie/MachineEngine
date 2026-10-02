package fr.robie.machineengine.api.process;

import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The machine's process slot (ARCHITECTURE.md §13, D15). Every operation is one transaction:
 * resources are never lost. Owner thread only.
 */
@ApiStatus.NonExtendable
public interface ProcessControl {

    @Nullable ProcessView current();

    /**
     * Consumes the recipe's inputs into the in-flight buffer and starts the process.
     *
     * @return the new process, or an error (e.g. insufficient input, a process already running)
     */
    @NotNull Result<ProcessView> start(@NotNull ProcessingRecipe recipe, @NotNull ProcessBinding binding);

    /**
     * Advances by the ticks elapsed since the machine last ticked: pays costs, makes progress, and
     * delivers outputs when done. Retries delivering outputs or returning inputs if pending.
     */
    @NotNull ProcessStatus advance();

    /** Like {@link #advance()}, but by exactly {@code ticks} ticks (at most what remains). */
    @NotNull ProcessStatus advance(long ticks);

    /** Cancels the process and returns its in-flight inputs to the input storages. */
    @NotNull ProcessStatus cancel();
}
