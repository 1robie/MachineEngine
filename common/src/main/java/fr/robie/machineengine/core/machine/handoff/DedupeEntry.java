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

    /**
     * Entries a target keeps per source machine (sources are its neighbours, so at most 6), covering
     * crashes that restore a source a few transfers behind its target.
     */
    public static final int PER_SOURCE = 16;

    public DedupeEntry {
        accepted = List.copyOf(accepted);
    }
}
