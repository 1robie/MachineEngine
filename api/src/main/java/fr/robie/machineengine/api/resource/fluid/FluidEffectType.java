package fr.robie.machineengine.api.resource.fluid;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A kind of {@link FluidEffect} that definitions and {@code fluids.yml} can use by key, like
 * {@link fr.robie.machineengine.api.definition.BehaviorType} for machines.
 *
 * <pre>{@code
 * effects:
 *   - type: machineengine:potion     # this key
 *     effect: minecraft:slowness     # the rest goes to the parser
 * }</pre>
 */
public final class FluidEffectType implements Keyed {
    private final Key key;
    private final Parser parser;

    private FluidEffectType(Key key, Parser parser) {
        this.key = key;
        this.parser = parser;
    }

    public static @NotNull FluidEffectType of(@NotNull Key key, @NotNull Parser parser) {
        return new FluidEffectType(Objects.requireNonNull(key), Objects.requireNonNull(parser));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** @return the effect, or an error describing what is wrong with {@code config} */
    public @NotNull Result<FluidEffect> parse(@NotNull DataMap config) {
        return this.parser.parse(config);
    }

    @Override
    public String toString() {
        return "FluidEffectType[" + this.key.asString() + "]";
    }

    @FunctionalInterface
    public interface Parser {
        @NotNull Result<FluidEffect> parse(@NotNull DataMap config);
    }
}
