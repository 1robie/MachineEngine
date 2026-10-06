package fr.robie.machineengine.api.transfer;

import fr.robie.machineengine.api.error.MachineError;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Outcome of a transfer (ARCHITECTURE.md §10.3). Simulation is a mode (an aborted transaction),
 * not an outcome.
 *
 * @param reason why less than requested was moved, if known
 */
public record TransferResult(@NotNull Status status, long requested, long moved, @Nullable MachineError reason) {

    public enum Status {
        /** Everything requested was moved. */
        COMPLETE,
        /** Some, but not all, was moved. */
        PARTIAL,
        /** Nothing was moved. */
        REJECTED
    }

    public static @NotNull TransferResult of(long requested, long moved, @Nullable MachineError reason) {
        Status status = moved == requested ? Status.COMPLETE : moved > 0 ? Status.PARTIAL : Status.REJECTED;
        return new TransferResult(status, requested, moved, status == Status.COMPLETE ? null : reason);
    }
}
