package fr.robie.machineengine.core.machine.handoff;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A handoff a target already accepted, kept so a re-delivery after a crash is answered with the
 * same result instead of inserting twice (§16.3).
 *
 * @param accepted amount accepted of each delivered stack, in order
 */
public record DedupeEntry(@NotNull TransferId id, @NotNull List<Long> accepted) {

    /** Entries kept per target machine. */
    public static final int CAPACITY = 64;

    public DedupeEntry {
        accepted = List.copyOf(accepted);
    }
}
