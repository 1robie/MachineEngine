package fr.robie.machineengine.api.platform;

import org.jetbrains.annotations.ApiStatus;

/** A task scheduled with the {@link Scheduler}: delayed or repeating, cancellable from any thread. */
@ApiStatus.NonExtendable
public interface Task {

    /**
     * Cancels the task: it does not run again. A run that has already started finishes. Cancelling
     * twice, or after the last run, does nothing.
     */
    void cancel();

    /** @return whether {@link #cancel()} was called */
    boolean isCancelled();
}
