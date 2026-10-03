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
 * @param extraOutputs more storages outputs go to when {@code output} does not take them (e.g. an
 *                item slot and a chemical tank); empty (or {@code null}) for none
 */
public record ProcessBinding(@NotNull List<String> inputs, @NotNull String output, @Nullable List<String> costs,
                             @Nullable List<String> extraOutputs) {

    public ProcessBinding {
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(output);
        costs = costs == null ? List.of() : List.copyOf(costs);
        extraOutputs = extraOutputs == null ? List.of() : List.copyOf(extraOutputs);
    }

    public ProcessBinding(@NotNull List<String> inputs, @NotNull String output, @Nullable List<String> costs) {
        this(inputs, output, costs, null);
    }

    /** Every output storage, in the order outputs try them. */
    public @NotNull List<String> outputs() {
        if (this.extraOutputs.isEmpty()) return List.of(this.output);
        List<String> all = new java.util.ArrayList<>(List.of(this.output));
        all.addAll(this.extraOutputs);
        return List.copyOf(all);
    }

    /** This binding, with outputs also going to {@code storages}. */
    public @NotNull ProcessBinding withExtraOutputs(@NotNull String... storages) {
        return new ProcessBinding(this.inputs, this.output, this.costs, List.of(storages));
    }

    public static @NotNull ProcessBinding of(@NotNull String input, @NotNull String output) {
        return new ProcessBinding(List.of(input), output, List.of());
    }

    public @NotNull ProcessBinding withCosts(@NotNull String... costs) {
        return new ProcessBinding(this.inputs, this.output, List.of(costs), this.extraOutputs);
    }

    /** Storages costs are paid from, in order; never empty unless there are no inputs either. */
    public @NotNull List<String> costStorages() {
        if (!this.costs.isEmpty()) return this.costs;
        return this.inputs.isEmpty() ? List.of() : List.of(this.inputs.getFirst());
    }
}
