package fr.robie.machineengine.api.resource.fluid;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A kind of {@link FluidTexture} that definitions and {@code fluids.yml} can use by key: a file, a
 * recolored vanilla pattern, a generated pattern, or an addon's own generator.
 *
 * <pre>{@code
 * look:
 *   still:
 *     source: machineengine:recolor   # this key
 *     pattern: lava                   # the rest goes to the parser
 *     from: "#0A080E"
 *     to: "#4E3E60"
 * }</pre>
 */
public final class FluidTextureType implements Keyed {
    private final Key key;
    private final Parser parser;

    private FluidTextureType(Key key, Parser parser) {
        this.key = key;
        this.parser = parser;
    }

    public static @NotNull FluidTextureType of(@NotNull Key key, @NotNull Parser parser) {
        return new FluidTextureType(Objects.requireNonNull(key), Objects.requireNonNull(parser));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** @return the texture, or an error describing what is wrong with {@code config} */
    public @NotNull Result<FluidTexture> parse(@NotNull DataMap config) {
        return this.parser.parse(config);
    }

    @Override
    public String toString() {
        return "FluidTextureType[" + this.key.asString() + "]";
    }

    @FunctionalInterface
    public interface Parser {
        @NotNull Result<FluidTexture> parse(@NotNull DataMap config);
    }
}
