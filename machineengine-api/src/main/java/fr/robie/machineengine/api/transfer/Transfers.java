package fr.robie.machineengine.api.transfer;

import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.resource.ResourceVariant;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/** Transfer helpers built on {@link ResourceHandler}s and nested transactions. */
public final class Transfers {

    private Transfers() {
    }

    /**
     * Moves up to {@code maxAmount} of variants matching {@code filter} from {@code source} to
     * {@code target} inside {@code transaction}. Each variant moves in its own nested transaction,
     * so a misbehaving handler can never lose or create resources.
     */
    public static @NotNull TransferResult move(@NotNull ResourceHandler source, @NotNull ResourceHandler target,
                                               @NotNull Predicate<ResourceVariant> filter, long maxAmount,
                                               @NotNull TransactionContext transaction) {
        requireNonNegative(maxAmount);
        if (maxAmount == 0) return TransferResult.of(0, 0, null);
        if (!source.supportsExtraction() || !target.supportsInsertion()) {
            return TransferResult.of(maxAmount, 0, new MachineError.TransferRejected(
                    !source.supportsExtraction() ? "source does not allow extraction" : "target does not allow insertion", null));
        }
        long moved = 0;
        for (StorageView view : source.views()) {
            if (moved == maxAmount) break;
            ResourceVariant variant = view.variant();
            if (view.isEmpty() || !filter.test(variant)) continue;
            long wanted = Math.min(maxAmount - moved, view.amount());
            long accepted = simulateInsert(target, variant, wanted, transaction);
            if (accepted == 0) continue;
            try (Transaction nested = transaction.openNested()) {
                long extracted = source.extract(variant, accepted, nested);
                long inserted = extracted == 0 ? 0 : target.insert(variant, extracted, nested);
                if (inserted == extracted && inserted > 0) {
                    nested.commit();
                    moved += inserted;
                }
            }
        }
        return TransferResult.of(maxAmount, moved,
                new MachineError.TransferRejected("the target accepted less than requested", null));
    }

    /** @return how much {@code handler} would accept, without changing it */
    public static long simulateInsert(@NotNull ResourceHandler handler, @NotNull ResourceVariant variant, long maxAmount,
                                      @NotNull TransactionContext transaction) {
        try (Transaction simulation = transaction.openNested()) {
            return handler.insert(variant, maxAmount, simulation);
        }
    }

    /** @return how much {@code handler} would give, without changing it */
    public static long simulateExtract(@NotNull ResourceHandler handler, @NotNull ResourceVariant variant, long maxAmount,
                                       @NotNull TransactionContext transaction) {
        try (Transaction simulation = transaction.openNested()) {
            return handler.extract(variant, maxAmount, simulation);
        }
    }

    static void requireNonNegative(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must not be negative: " + amount);
    }
}
