package fr.robie.machineengine.api.process;

/** State of a machine's process after an operation. */
public enum ProcessStatus {
    /** No process. */
    IDLE,
    /** Progressing. */
    RUNNING,
    /** Paused: a per-tick cost could not be paid. */
    WAITING_FOR_COST,
    /** Finished, but the outputs do not fit; retried when output space frees up. */
    OUTPUT_BLOCKED,
    /** Finished and outputs delivered; the machine is idle again. */
    COMPLETED,
    /** Cancelled; inputs are being returned but do not all fit yet. */
    RETURNING
}
