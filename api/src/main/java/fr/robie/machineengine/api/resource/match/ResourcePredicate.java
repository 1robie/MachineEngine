package fr.robie.machineengine.api.resource.match;

import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A condition on resource variants (ARCHITECTURE.md §9.3). Tags are evaluated against the snapshot
 * passed in, so predicates stay valid across tag reloads. Matching never implies conversion.
 */
@FunctionalInterface
public interface ResourcePredicate {

    boolean test(@NotNull ResourceVariant variant, @NotNull ResourceTags tags);

    /** @return a plain predicate evaluated against one tag snapshot */
    default @NotNull Predicate<ResourceVariant> bind(@NotNull ResourceTags tags) {
        Objects.requireNonNull(tags);
        return variant -> test(variant, tags);
    }

    default @NotNull ResourcePredicate and(@NotNull ResourcePredicate other) {
        Objects.requireNonNull(other);
        return (variant, tags) -> test(variant, tags) && other.test(variant, tags);
    }

    default @NotNull ResourcePredicate or(@NotNull ResourcePredicate other) {
        Objects.requireNonNull(other);
        return (variant, tags) -> test(variant, tags) || other.test(variant, tags);
    }

    default @NotNull ResourcePredicate negate() {
        return (variant, tags) -> !test(variant, tags);
    }

    static @NotNull ResourcePredicate any() {
        return (variant, tags) -> true;
    }

    static @NotNull ResourcePredicate none() {
        return (variant, tags) -> false;
    }

    /** Matches exactly this registered type (by reference, D9). */
    static @NotNull ResourcePredicate type(@NotNull ResourceType type) {
        Objects.requireNonNull(type);
        return (variant, tags) -> variant.type() == type;
    }

    static @NotNull ResourcePredicate types(@NotNull ResourceType... types) {
        Set<ResourceType> set = Set.of(types);
        return (variant, tags) -> set.contains(variant.type());
    }

    static @NotNull ResourcePredicate category(@NotNull ResourceCategory category) {
        Objects.requireNonNull(category);
        return (variant, tags) -> variant.category() == category;
    }

    static @NotNull ResourcePredicate tag(@NotNull Key tag) {
        Objects.requireNonNull(tag);
        return (variant, tags) -> tags.contains(tag, variant.type());
    }

    static @NotNull ResourcePredicate exactly(@NotNull ResourceVariant expected) {
        Objects.requireNonNull(expected);
        return (variant, tags) -> variant.equals(expected);
    }

    static @NotNull ResourcePredicate hasComponent(@NotNull ResourceComponentType<?> component) {
        Objects.requireNonNull(component);
        return (variant, tags) -> variant.components().has(component);
    }

    /** Matches variants whose {@code component} is present and satisfies {@code condition}. */
    static <T> @NotNull ResourcePredicate component(@NotNull ResourceComponentType<T> component,
                                                    @NotNull Predicate<? super T> condition) {
        Objects.requireNonNull(component);
        Objects.requireNonNull(condition);
        return (variant, tags) -> {
            T value = variant.components().get(component);
            return value != null && condition.test(value);
        };
    }
}
