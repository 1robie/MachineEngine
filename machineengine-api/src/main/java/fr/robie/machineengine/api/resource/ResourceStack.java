package fr.robie.machineengine.api.resource;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** An immutable amount of a {@link ResourceVariant}. The amount is never negative. */
public record ResourceStack(@NotNull ResourceVariant variant, long amount) {

    public ResourceStack {
        Objects.requireNonNull(variant);
        if (amount < 0) throw new IllegalArgumentException("Resource amount must not be negative: " + amount);
    }

    public static @NotNull ResourceStack of(@NotNull ResourceType type, long amount) {
        return new ResourceStack(type.variant(), amount);
    }

    public @NotNull ResourceType type() {
        return this.variant.type();
    }

    public boolean isEmpty() {
        return this.amount == 0;
    }

    public @NotNull ResourceStack withAmount(long amount) {
        return amount == this.amount ? this : new ResourceStack(this.variant, amount);
    }

    @Override
    public @NotNull String toString() {
        return this.amount + " " + this.variant;
    }
}
