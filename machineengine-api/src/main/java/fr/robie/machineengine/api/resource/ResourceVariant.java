package fr.robie.machineengine.api.resource;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * What a resource is, without an amount: a {@link ResourceType} plus its components. Storages are
 * keyed by variant; two stacks of equal variants always stack.
 */
public record ResourceVariant(@NotNull ResourceType type, @NotNull ResourceComponents components) {

    public ResourceVariant {
        Objects.requireNonNull(type);
        Objects.requireNonNull(components);
    }

    public static @NotNull ResourceVariant of(@NotNull ResourceType type) {
        return type.variant();
    }

    public @NotNull ResourceCategory category() {
        return this.type.category();
    }

    public <T> @NotNull ResourceVariant with(@NotNull ResourceComponentType<T> component, @NotNull T value) {
        return new ResourceVariant(this.type, this.components.with(component, value));
    }

    public @NotNull ResourceStack stack(long amount) {
        return new ResourceStack(this, amount);
    }

    @Override
    public @NotNull String toString() {
        return this.components.isEmpty()
                ? this.type.key().asString()
                : this.type.key().asString() + this.components;
    }
}
