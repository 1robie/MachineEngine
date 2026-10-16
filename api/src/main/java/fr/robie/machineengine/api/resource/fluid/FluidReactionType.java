package fr.robie.machineengine.api.resource.fluid;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A kind of {@link FluidReaction} that definitions and {@code fluids.yml} can use by key.
 *
 * <pre>{@code
 * mixing:
 *   - with: mymod:fluid/acid
 *     result: machineengine:block   # this key
 *     block: minecraft:calcite      # the rest goes to the parser
 * }</pre>
 */
public final class FluidReactionType implements Keyed {
    private final Key key;
    private final Parser parser;

    private FluidReactionType(Key key, Parser parser) {
        this.key = key;
        this.parser = parser;
    }

    public static @NotNull FluidReactionType of(@NotNull Key key, @NotNull Parser parser) {
        return new FluidReactionType(Objects.requireNonNull(key), Objects.requireNonNull(parser));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** @return the reaction, or an error describing what is wrong with {@code config} */
    public @NotNull Result<FluidReaction> parse(@NotNull DataMap config) {
        return this.parser.parse(config);
    }

    @Override
    public String toString() {
        return "FluidReactionType[" + this.key.asString() + "]";
    }

    @FunctionalInterface
    public interface Parser {
        @NotNull Result<FluidReaction> parse(@NotNull DataMap config);
    }
}
