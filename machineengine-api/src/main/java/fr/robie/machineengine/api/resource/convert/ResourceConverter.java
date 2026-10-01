package fr.robie.machineengine.api.resource.convert;

import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * An explicitly registered conversion between two resource types (ARCHITECTURE.md §9.4).
 * MachineEngine never calls a converter on its own: machines, ports or transports that declare one
 * do. Conversions must be pure and deterministic.
 */
public final class ResourceConverter implements Keyed {
    private final Key key;
    private final ResourceType from;
    private final ResourceType to;
    private final Conversion conversion;

    private ResourceConverter(Key key, ResourceType from, ResourceType to, Conversion conversion) {
        this.key = key;
        this.from = from;
        this.to = to;
        this.conversion = conversion;
    }

    public static @NotNull ResourceConverter of(@NotNull Key key, @NotNull ResourceType from, @NotNull ResourceType to,
                                                @NotNull Conversion conversion) {
        return new ResourceConverter(Objects.requireNonNull(key), Objects.requireNonNull(from), Objects.requireNonNull(to),
                Objects.requireNonNull(conversion));
    }

    /**
     * Converts in whole units: every {@code inputAmount} of {@code from} becomes {@code outputAmount}
     * of {@code to}'s default variant. Leftovers smaller than {@code inputAmount} are not consumed.
     */
    public static @NotNull ResourceConverter ratio(@NotNull Key key, @NotNull ResourceType from, long inputAmount,
                                                   @NotNull ResourceType to, long outputAmount) {
        if (inputAmount <= 0 || outputAmount <= 0) {
            throw new IllegalArgumentException("Conversion amounts must be positive");
        }
        return of(key, from, to, (input, available) -> {
            long units = available / inputAmount;
            if (units == 0) return null;
            // Clamp so the output amount cannot overflow.
            units = Math.min(units, Long.MAX_VALUE / outputAmount);
            return new ConversionResult(units * inputAmount, to.stack(units * outputAmount));
        });
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull ResourceType from() {
        return this.from;
    }

    public @NotNull ResourceType to() {
        return this.to;
    }

    /**
     * @param input     the variant to convert; must be of type {@link #from()}
     * @param available how much of it may be consumed
     * @return what is consumed and produced, or {@code null} if nothing can be converted
     */
    public @Nullable ConversionResult convert(@NotNull ResourceVariant input, long available) {
        if (input.type() != this.from) {
            throw new IllegalArgumentException(this.key.asString() + " converts " + this.from.key().asString()
                    + ", not " + input.type().key().asString());
        }
        if (available <= 0) return null;
        ConversionResult result = this.conversion.convert(input, available);
        if (result != null && (result.consumed() <= 0 || result.consumed() > available || result.output().type() != this.to)) {
            throw new IllegalStateException(this.key.asString() + " returned an invalid conversion: " + result);
        }
        return result;
    }

    @Override
    public String toString() {
        return "ResourceConverter[" + this.key.asString() + ": " + this.from.key().asString() + " -> " + this.to.key().asString() + "]";
    }

    @FunctionalInterface
    public interface Conversion {
        @Nullable ConversionResult convert(@NotNull ResourceVariant input, long available);
    }

    /**
     * @param consumed how much input is used (0 &lt; consumed &le; available)
     * @param output   what is produced
     */
    public record ConversionResult(long consumed, @NotNull ResourceStack output) {
    }
}
