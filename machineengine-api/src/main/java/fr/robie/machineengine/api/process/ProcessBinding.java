package fr.robie.machineengine.api.process;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Which machine storages a process uses.
 *
 * @param inputs  storages inputs are taken from (and returned to on cancel), in order
 * @param output  storage outputs go to
 * @param costs   storages per-tick costs are paid from: each cost is taken from these storages in
 *                order (e.g. energy, then a chemical tank); empty (or {@code null}) for the first
 *                input storage
 */
public record ProcessBinding(@NotNull List<String> inputs, @NotNull String output, @Nullable List<String> costs) {

    public ProcessBinding {
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(output);
        costs = costs == null ? List.of() : List.copyOf(costs);
    }

    public static @NotNull ProcessBinding of(@NotNull String input, @NotNull String output) {
        return new ProcessBinding(List.of(input), output, List.of());
    }

    public @NotNull ProcessBinding withCosts(@NotNull String... costs) {
        return new ProcessBinding(this.inputs, this.output, List.of(costs));
    }

    /** Storages costs are paid from, in order; never empty unless there are no inputs either. */
    public @NotNull List<String> costStorages() {
        if (!this.costs.isEmpty()) return this.costs;
        return this.inputs.isEmpty() ? List.of() : List.of(this.inputs.getFirst());
    }
}
