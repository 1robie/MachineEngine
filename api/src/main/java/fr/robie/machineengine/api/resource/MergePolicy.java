package fr.robie.machineengine.api.resource;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Decides whether a storage holding one variant of a type may accept another variant of the
 * <em>same type</em>, and what the stored variant becomes (ARCHITECTURE.md §9.2). Different types
 * never merge, whatever the policy says.
 *
 * <p>Implementations must be deterministic and must not use floating point.</p>
 */
public interface MergePolicy {

    /** Only equal variants merge. The default for every category. */
    MergePolicy STRICT = new MergePolicy() {
        @Override
        public boolean canMerge(@NotNull ResourceVariant existing, @NotNull ResourceVariant incoming) {
            return existing.equals(incoming);
        }

        @Override
        public @NotNull ResourceVariant merge(@NotNull ResourceVariant existing, long existingAmount,
                                              @NotNull ResourceVariant incoming, long incomingAmount) {
            return existing;
        }

        @Override
        public String toString() {
            return "MergePolicy.STRICT";
        }
    };

    boolean canMerge(@NotNull ResourceVariant existing, @NotNull ResourceVariant incoming);

    /**
     * Only called when {@link #canMerge} returned {@code true}.
     *
     * @return the variant stored after adding {@code incomingAmount} of {@code incoming} to
     *         {@code existingAmount} of {@code existing}
     */
    @NotNull ResourceVariant merge(@NotNull ResourceVariant existing, long existingAmount,
                                   @NotNull ResourceVariant incoming, long incomingAmount);

    /**
     * Merges variants that differ only in {@code component}, replacing it by the amount-weighted
     * average (rounded down). Example: mixing 100 mB of steam at 400 K into 300 mB at 300 K gives
     * 400 mB at 325 K.
     */
    static @NotNull MergePolicy weightedAverage(@NotNull ResourceComponentType<Long> component) {
        Objects.requireNonNull(component);
        return new MergePolicy() {
            @Override
            public boolean canMerge(@NotNull ResourceVariant existing, @NotNull ResourceVariant incoming) {
                return existing.type() == incoming.type()
                        && existing.components().has(component) == incoming.components().has(component)
                        && existing.components().without(component).equals(incoming.components().without(component));
            }

            @Override
            public @NotNull ResourceVariant merge(@NotNull ResourceVariant existing, long existingAmount,
                                                  @NotNull ResourceVariant incoming, long incomingAmount) {
                Long a = existing.components().get(component);
                Long b = incoming.components().get(component);
                if (a == null || b == null || a.equals(b) || existingAmount + incomingAmount == 0) return existing;
                BigInteger weighted = BigInteger.valueOf(a).multiply(BigInteger.valueOf(existingAmount))
                        .add(BigInteger.valueOf(b).multiply(BigInteger.valueOf(incomingAmount)));
                BigInteger[] quotient = weighted.divideAndRemainder(BigInteger.valueOf(existingAmount).add(BigInteger.valueOf(incomingAmount)));
                // Floor division, also for negative values.
                BigInteger average = quotient[1].signum() < 0 ? quotient[0].subtract(BigInteger.ONE) : quotient[0];
                return existing.with(component, average.longValueExact());
            }

            @Override
            public String toString() {
                return "MergePolicy.weightedAverage(" + component.key().asString() + ")";
            }
        };
    }
}
