package fr.robie.machineengine.test;

import fr.robie.machineengine.api.upgrade.Modifier;
import fr.robie.machineengine.api.upgrade.ModifierType;
import fr.robie.machineengine.api.upgrade.Modifiers;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The D16 stacking rule: long math must give exactly what the BigInteger formula gives, overflow included. */
class ModifiersTest {
    private static final ModifierType RATE = ModifierType.nonNegative(Key.key("test", "rate"));
    private static final ModifierType SIGNED = ModifierType.of(Key.key("test", "signed"), -1_000_000, Long.MAX_VALUE);
    private static final ModifierType OTHER = ModifierType.nonNegative(Key.key("test", "other"));

    @Test
    void stacksAddThenBaseMultipliersThenTotalMultipliers() {
        List<Modifier> modifiers = List.of(Modifier.add(RATE, 10), Modifier.addMultipliedBase(RATE, 500),
                Modifier.addMultipliedBase(RATE, 250), Modifier.multiplyTotal(RATE, 1000), Modifier.add(OTHER, 99));
        assertEquals((100 + 10) * 1750 / 1000 * 2, Modifiers.apply(RATE, 100, modifiers));
        assertEquals(100, Modifiers.apply(RATE, 100, List.of()), "no modifier: the base");
        assertEquals(0, Modifiers.apply(RATE, 100, List.of(Modifier.add(RATE, -500))), "clamped to the type's range");
    }

    @Test
    void roundsDownTowardsNegativeInfinity() {
        assertEquals(-2, Modifiers.apply(SIGNED, -3, List.of(Modifier.addMultipliedBase(SIGNED, -500))), "-1.5 rounds to -2");
    }

    @Test
    void overflowFallsBackToExactMath() {
        long huge = Long.MAX_VALUE / 2;
        assertEquals(Long.MAX_VALUE, Modifiers.apply(RATE, huge, List.of(Modifier.multiplyTotal(RATE, 3000))), "clamped, not wrapped");
        assertEquals(huge, Modifiers.apply(RATE, huge, List.of(Modifier.multiplyTotal(RATE, 1000), Modifier.multiplyTotal(RATE, -500))),
                "an intermediate above Long.MAX_VALUE still gives the exact result");
    }

    @Test
    void matchesTheExactFormulaOnRandomInputs() {
        SplittableRandom random = new SplittableRandom(16);
        for (int run = 0; run < 20_000; run++) {
            ModifierType type = random.nextBoolean() ? RATE : SIGNED;
            long base = random.nextInt(4) == 0 ? random.nextLong() : random.nextLong(-10_000, 1_000_000);
            List<Modifier> modifiers = new ArrayList<>();
            for (int i = random.nextInt(6); i > 0; i--) {
                ModifierType target = random.nextInt(5) == 0 ? OTHER : type;
                modifiers.add(switch (random.nextInt(3)) {
                    case 0 -> Modifier.add(target, random.nextLong(-5_000, 5_000));
                    case 1 -> Modifier.addMultipliedBase(target, random.nextLong(-1_500, 5_000));
                    default -> Modifier.multiplyTotal(target, random.nextLong(-1_500, 5_000));
                });
            }
            assertEquals(reference(type, base, modifiers), Modifiers.apply(type, base, modifiers), () -> base + " " + modifiers);
        }
    }

    /** D16 with BigInteger throughout. */
    private static long reference(ModifierType type, long base, List<Modifier> modifiers) {
        BigInteger thousand = BigInteger.valueOf(1000);
        BigInteger value = BigInteger.valueOf(base);
        BigInteger multipliedBase = BigInteger.ZERO;
        for (Modifier modifier : modifiers) {
            if (modifier.type() != type) continue;
            if (modifier.operation() == Modifier.Operation.ADD) value = value.add(BigInteger.valueOf(modifier.value()));
            if (modifier.operation() == Modifier.Operation.ADD_MULTIPLIED_BASE) multipliedBase = multipliedBase.add(BigInteger.valueOf(modifier.value()));
        }
        value = floorDiv(value.multiply(thousand.add(multipliedBase)), thousand);
        for (Modifier modifier : modifiers) {
            if (modifier.type() == type && modifier.operation() == Modifier.Operation.MULTIPLY_TOTAL) {
                value = floorDiv(value.multiply(thousand.add(BigInteger.valueOf(modifier.value()))), thousand);
            }
        }
        return value.max(BigInteger.valueOf(type.min())).min(BigInteger.valueOf(type.max())).longValueExact();
    }

    private static BigInteger floorDiv(BigInteger dividend, BigInteger divisor) {
        BigInteger[] result = dividend.divideAndRemainder(divisor);
        return result[1].signum() < 0 ? result[0].subtract(BigInteger.ONE) : result[0];
    }
}
