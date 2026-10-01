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
 * @param costs   storage per-tick costs are paid from, or {@code null} for the first input storage
 */
public record ProcessBinding(@NotNull List<String> inputs, @NotNull String output, @Nullable String costs) {

    public ProcessBinding {
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(output);
    }

    public static @NotNull ProcessBinding of(@NotNull String input, @NotNull String output) {
        return new ProcessBinding(List.of(input), output, null);
    }

    public @NotNull ProcessBinding withCosts(@NotNull String costs) {
        return new ProcessBinding(this.inputs, this.output, costs);
    }

    public @Nullable String costStorage() {
        return this.costs != null ? this.costs : this.inputs.isEmpty() ? null : this.inputs.getFirst();
    }
}
