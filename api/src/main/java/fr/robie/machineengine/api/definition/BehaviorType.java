package fr.robie.machineengine.api.definition;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.machine.MachineBehavior;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A machine behavior that data-driven definitions can use by key (ARCHITECTURE.md §29). Addons
 * register Java behaviors as behavior types so server owners can build machines from them in YAML.
 *
 * <pre>{@code
 * behavior:
 *   type: mymod:smelting       # this key
 *   ...                        # passed to the parser
 * }</pre>
 */
public final class BehaviorType implements Keyed {
    private final Key key;
    private final Parser parser;

    private BehaviorType(Key key, Parser parser) {
        this.key = key;
        this.parser = parser;
    }

    public static @NotNull BehaviorType of(@NotNull Key key, @NotNull Parser parser) {
        return new BehaviorType(Objects.requireNonNull(key), Objects.requireNonNull(parser));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** @return the behavior factory, or an error describing what is wrong with {@code config} */
    public @NotNull Result<MachineBehavior.Factory> parse(@NotNull DataMap config, @NotNull DefinitionContext context) {
        return this.parser.parse(config, context);
    }

    @Override
    public String toString() {
        return "BehaviorType[" + this.key.asString() + "]";
    }

    @FunctionalInterface
    public interface Parser {
        @NotNull Result<MachineBehavior.Factory> parse(@NotNull DataMap config, @NotNull DefinitionContext context);
    }
}
