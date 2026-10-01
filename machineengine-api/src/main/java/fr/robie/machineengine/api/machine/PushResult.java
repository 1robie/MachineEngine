package fr.robie.machineengine.api.machine;

import org.jetbrains.annotations.NotNull;

/**
 * Outcome of {@link MachineContext#push}.
 *
 * @param moved amount that left this machine: delivered directly, or placed in escrow for a
 *              cross-region handoff (§16.3)
 */
public record PushResult(@NotNull Kind kind, long moved) {

    public enum Kind {
        /** Moved directly; {@link #moved()} arrived at the target. */
        DELIVERED,
        /** Sent to another region; {@link #moved()} is in escrow until the target answers. */
        HANDOFF,
        /** A handoff on this route is still in flight; nothing was sent (backpressure). */
        BUSY,
        /** No machine, port or loaded chunk on that side. */
        NO_TARGET
    }

    public static final PushResult NO_TARGET = new PushResult(Kind.NO_TARGET, 0);
    public static final PushResult BUSY = new PushResult(Kind.BUSY, 0);
}
