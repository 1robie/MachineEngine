package fr.robie.machineengine.api.transfer;

import fr.robie.machineengine.api.resource.ResourceVariant;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Something resources can be inserted into or extracted from (ARCHITECTURE.md §10.1). Every change
 * happens inside a transaction; nothing is removed before the caller commits.
 *
 * <p>Implementations must conserve resources: an insert that returns {@code n} stores exactly
 * {@code n}, an extract that returns {@code n} removes exactly {@code n}.</p>
 */
public interface ResourceHandler {

    /**
     * @param maxAmount the most to insert; must not be negative
     * @return how much was accepted, between 0 and {@code maxAmount}
     */
    long insert(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction);

    /**
     * @param maxAmount the most to extract; must not be negative
     * @return how much was removed, between 0 and {@code maxAmount}
     */
    long extract(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction);

    /** @return a snapshot of the current contents, one view per slot */
    @NotNull List<StorageView> views();

    default boolean supportsInsertion() {
        return true;
    }

    default boolean supportsExtraction() {
        return true;
    }

    /** @return the total amount of exactly {@code variant} held */
    default long amountOf(@NotNull ResourceVariant variant) {
        long total = 0;
        for (StorageView view : views()) {
            if (variant.equals(view.variant())) total = saturatedAdd(total, view.amount());
        }
        return total;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
