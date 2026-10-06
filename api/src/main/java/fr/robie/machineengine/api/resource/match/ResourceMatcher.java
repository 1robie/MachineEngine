package fr.robie.machineengine.api.resource.match;

import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A {@link ResourcePredicate} plus an amount requirement, used by ports and recipes.
 *
 * @param predicate which variants match
 * @param amount    the minimum amount required; 0 means no requirement
 */
public record ResourceMatcher(@NotNull ResourcePredicate predicate, long amount) {

    public ResourceMatcher {
        Objects.requireNonNull(predicate);
        if (amount < 0) throw new IllegalArgumentException("Required amount must not be negative: " + amount);
    }

    public static @NotNull ResourceMatcher of(@NotNull ResourcePredicate predicate) {
        return new ResourceMatcher(predicate, 0);
    }

    public static @NotNull ResourceMatcher of(@NotNull ResourcePredicate predicate, long amount) {
        return new ResourceMatcher(predicate, amount);
    }

    public boolean matches(@NotNull ResourceVariant variant, @NotNull ResourceTags tags) {
        return this.predicate.test(variant, tags);
    }

    /** @return whether the stack matches and holds at least {@link #amount()} */
    public boolean matches(@NotNull ResourceStack stack, @NotNull ResourceTags tags) {
        return stack.amount() >= this.amount && this.predicate.test(stack.variant(), tags);
    }
}
