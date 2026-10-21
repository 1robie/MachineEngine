package fr.robie.machineengine.api.upgrade;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;

/**
 * Applies modifiers with the D16 stacking rule:
 * {@code base → +ΣADD → ×(1+ΣADD_MULTIPLIED_BASE) → ×Π(1+MULTIPLY_TOTAL) → clamp}. Integer math,
 * rounded down at each multiplication, so results are deterministic.
 */
public final class Modifiers {
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);

    private Modifiers() {
    }

    /** Applies the modifiers of {@code type} among {@code modifiers}; others are ignored. */
    public static long apply(@NotNull ModifierType type, long base, @NotNull Iterable<Modifier> modifiers) {
        // Called on every capacity and rate read: long math first, BigInteger only when it would overflow.
        try {
            return applyExact(type, base, modifiers);
        } catch (ArithmeticException overflow) {
            return applyBig(type, base, modifiers);
        }
    }

    private static long applyExact(ModifierType type, long base, Iterable<Modifier> modifiers) {
        long value = base;
        long multipliedBase = 0;
        boolean multiplyTotal = false;
        for (Modifier modifier : modifiers) {
            if (modifier.type() != type) continue;
            switch (modifier.operation()) {
                case ADD -> value = Math.addExact(value, modifier.value());
                case ADD_MULTIPLIED_BASE -> multipliedBase = Math.addExact(multipliedBase, modifier.value());
                case MULTIPLY_TOTAL -> multiplyTotal = true;
            }
        }
        if (multipliedBase != 0) value = Math.floorDiv(Math.multiplyExact(value, Math.addExact(1000L, multipliedBase)), 1000L);
        if (multiplyTotal) {
            for (Modifier modifier : modifiers) {
                if (modifier.type() == type && modifier.operation() == Modifier.Operation.MULTIPLY_TOTAL) {
                    value = Math.floorDiv(Math.multiplyExact(value, Math.addExact(1000L, modifier.value())), 1000L);
                }
            }
        }
        return Math.min(Math.max(value, type.min()), type.max());
    }

    private static long applyBig(ModifierType type, long base, Iterable<Modifier> modifiers) {
        BigInteger value = BigInteger.valueOf(base);
        BigInteger multipliedBase = BigInteger.ZERO;
        for (Modifier modifier : modifiers) {
            if (modifier.type() != type) continue;
            switch (modifier.operation()) {
                case ADD -> value = value.add(BigInteger.valueOf(modifier.value()));
                case ADD_MULTIPLIED_BASE -> multipliedBase = multipliedBase.add(BigInteger.valueOf(modifier.value()));
                case MULTIPLY_TOTAL -> { }
            }
        }
        value = floorDiv(value.multiply(THOUSAND.add(multipliedBase)), THOUSAND);
        for (Modifier modifier : modifiers) {
            if (modifier.type() == type && modifier.operation() == Modifier.Operation.MULTIPLY_TOTAL) {
                value = floorDiv(value.multiply(THOUSAND.add(BigInteger.valueOf(modifier.value()))), THOUSAND);
            }
        }
        BigInteger min = BigInteger.valueOf(type.min());
        BigInteger max = BigInteger.valueOf(type.max());
        return value.max(min).min(max).longValueExact();
    }

    private static BigInteger floorDiv(BigInteger dividend, BigInteger divisor) {
        BigInteger[] result = dividend.divideAndRemainder(divisor);
        return result[1].signum() < 0 ? result[0].subtract(BigInteger.ONE) : result[0];
    }
}
