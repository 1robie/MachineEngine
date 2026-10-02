package fr.robie.machineengine.api.recipe;

import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A timed transformation run by the built-in process (ARCHITECTURE.md §13): consume
 * {@link #inputs()} when starting, pay {@link #costPerTick()} every tick for {@link #duration()}
 * ticks, then produce {@link #outputs()}.
 *
 * @param inputs      each matcher's {@linkplain ResourceMatcher#amount() amount} is consumed
 * @param outputs     produced on completion (may be empty: a sink)
 * @param duration    base ticks, scaled by {@code machineengine:process_duration}
 * @param costPerTick paid every tick, scaled by {@code machineengine:process_cost}; the process
 *                    pauses while it cannot be paid
 * @param chanceOutputs extra outputs, each produced with its chance (rolled when the process starts)
 */
public record ProcessingRecipe(@NotNull Key key, @NotNull RecipeType<ProcessingRecipe> type,
                               @NotNull List<ResourceMatcher> inputs, @NotNull List<ResourceStack> outputs,
                               long duration, @NotNull List<ResourceStack> costPerTick,
                               @NotNull List<ChanceOutput> chanceOutputs) implements Recipe {

    public ProcessingRecipe {
        Objects.requireNonNull(key);
        Objects.requireNonNull(type);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        costPerTick = List.copyOf(costPerTick);
        chanceOutputs = List.copyOf(chanceOutputs);
    }

    public ProcessingRecipe(@NotNull Key key, @NotNull RecipeType<ProcessingRecipe> type, @NotNull List<ResourceMatcher> inputs,
                            @NotNull List<ResourceStack> outputs, long duration, @NotNull List<ResourceStack> costPerTick) {
        this(key, type, inputs, outputs, duration, costPerTick, List.of());
    }

    public static @NotNull Builder builder(@NotNull Key key, @NotNull RecipeType<ProcessingRecipe> type) {
        return new Builder(key, type);
    }

    public static final class Builder {
        private final Key key;
        private final RecipeType<ProcessingRecipe> type;
        private final List<ResourceMatcher> inputs = new ArrayList<>();
        private final List<ResourceStack> outputs = new ArrayList<>();
        private final List<ResourceStack> costPerTick = new ArrayList<>();
        private final List<ChanceOutput> chanceOutputs = new ArrayList<>();
        private long duration = 1;

        private Builder(Key key, RecipeType<ProcessingRecipe> type) {
            this.key = key;
            this.type = type;
        }

        public @NotNull Builder input(@NotNull ResourceMatcher input) {
            this.inputs.add(input);
            return this;
        }

        public @NotNull Builder output(@NotNull ResourceStack output) {
            this.outputs.add(output);
            return this;
        }

        public @NotNull Builder costPerTick(@NotNull ResourceStack cost) {
            this.costPerTick.add(cost);
            return this;
        }

        /** An extra output produced with probability {@code chance} (0 to 1). */
        public @NotNull Builder chanceOutput(@NotNull ResourceStack output, double chance) {
            this.chanceOutputs.add(new ChanceOutput(output, chance));
            return this;
        }

        public @NotNull Builder duration(long ticks) {
            this.duration = ticks;
            return this;
        }

        public @NotNull ProcessingRecipe build() {
            return new ProcessingRecipe(this.key, this.type, this.inputs, this.outputs, this.duration, this.costPerTick, this.chanceOutputs);
        }
    }
}
