package fr.robie.machineengine.api.upgrade;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * One change to a {@link ModifierType}'s value.
 *
 * @param value for {@link Operation#ADD} an amount in the value's own unit; for the multiplying
 *              operations a fraction in permille: {@code 250} is +25 %, {@code -500} is −50 %
 */
public record Modifier(@NotNull ModifierType type, @NotNull Operation operation, long value) {

    public Modifier {
        Objects.requireNonNull(type);
        Objects.requireNonNull(operation);
    }

    public static @NotNull Modifier add(@NotNull ModifierType type, long amount) {
        return new Modifier(type, Operation.ADD, amount);
    }

    public static @NotNull Modifier addMultipliedBase(@NotNull ModifierType type, long permille) {
        return new Modifier(type, Operation.ADD_MULTIPLIED_BASE, permille);
    }

    public static @NotNull Modifier multiplyTotal(@NotNull ModifierType type, long permille) {
        return new Modifier(type, Operation.MULTIPLY_TOTAL, permille);
    }

    /** Applied in this order, like Minecraft attribute modifiers (D16). */
    public enum Operation {
        /** {@code value += amount} */
        ADD,
        /** {@code value *= 1 + Σ fractions}: fractions add up before multiplying */
        ADD_MULTIPLIED_BASE,
        /** {@code value *= (1 + fraction)} for each modifier */
        MULTIPLY_TOTAL
    }
}
