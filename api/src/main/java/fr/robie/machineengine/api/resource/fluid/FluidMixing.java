package fr.robie.machineengine.api.resource.fluid;

import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * What a placed fluid does when it meets other fluids: the first rule whose {@link Selector} matches
 * decides; without a match, both stop ({@link FluidReaction#STOP}). Read at runtime.
 */
public record FluidMixing(@NotNull List<Rule> rules) {
    /** No rules: a fluid stops where it meets another one. */
    public static final FluidMixing STOP = new FluidMixing(List.of());

    public FluidMixing {
        rules = List.copyOf(rules);
    }

    /** The reaction against {@code present}, given the current resource tags. */
    public @NotNull FluidReaction reactionWith(@NotNull ResourceType present, @NotNull ResourceTags tags) {
        for (Rule rule : this.rules) {
            if (rule.with().matches(present, tags)) return rule.reaction();
        }
        return FluidReaction.STOP;
    }

    /** One rule: when the fluid already there matches {@code with}, {@code reaction} decides. */
    public record Rule(@NotNull Selector with, @NotNull FluidReaction reaction) {
        public Rule {
            Objects.requireNonNull(with);
            Objects.requireNonNull(reaction);
        }
    }

    /** Which fluids a rule is about. */
    public sealed interface Selector {
        boolean matches(@NotNull ResourceType present, @NotNull ResourceTags tags);

        /** Any fluid, plain water included. */
        record Any() implements Selector {
            @Override
            public boolean matches(@NotNull ResourceType present, @NotNull ResourceTags tags) {
                return true;
            }
        }

        /** One fluid, by key ({@code machineengine:fluid/water} for plain water). */
        record Fluid(@NotNull Key key) implements Selector {
            @Override
            public boolean matches(@NotNull ResourceType present, @NotNull ResourceTags tags) {
                return present.key().equals(this.key);
            }
        }

        /** Every fluid in a resource tag. */
        record Tag(@NotNull Key tag) implements Selector {
            @Override
            public boolean matches(@NotNull ResourceType present, @NotNull ResourceTags tags) {
                return tags.members(this.tag).contains(present);
            }
        }
    }
}
