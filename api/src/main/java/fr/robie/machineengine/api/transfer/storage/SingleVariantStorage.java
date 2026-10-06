package fr.robie.machineengine.api.transfer.storage;

import fr.robie.machineengine.api.resource.MergePolicy;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.SnapshotParticipant;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.TransactionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One slot holding a single variant: a tank, a battery, an item slot. Different variants of the
 * same type combine according to the category's {@link MergePolicy}.
 *
 * <p>Owned by one thread (§16); not thread-safe. Subclasses may override {@link #onFinalCommit()}
 * to react to committed changes (dirty marking, wake-ups).</p>
 */
public class SingleVariantStorage extends SnapshotParticipant<SingleVariantStorage.Snapshot> implements ResourceHandler {
    private final long capacity;
    private final Predicate<ResourceVariant> filter;
    private final boolean insertion;
    private final boolean extraction;
    private @Nullable ResourceVariant variant;
    private long amount;

    protected SingleVariantStorage(@NotNull Builder builder) {
        this.capacity = builder.capacity;
        this.filter = builder.filter;
        this.insertion = builder.insertion;
        this.extraction = builder.extraction;
    }

    public static @NotNull Builder builder(long capacity) {
        return new Builder(capacity);
    }

    public static @NotNull SingleVariantStorage of(long capacity) {
        return builder(capacity).build();
    }

    @Override
    public long insert(@NotNull ResourceVariant incoming, long maxAmount, @NotNull TransactionContext transaction) {
        requireNonNegative(maxAmount);
        if (!this.insertion || maxAmount == 0 || !this.filter.test(incoming)) return 0;
        ResourceVariant current = this.variant;
        MergePolicy policy = incoming.category().mergePolicy();
        if (current != null && this.amount > 0 && !current.equals(incoming)
                && (current.type() != incoming.type() || !policy.canMerge(current, incoming))) {
            return 0;
        }
        long inserted = Math.min(maxAmount, capacity() - this.amount);
        if (inserted <= 0) return 0;
        updateSnapshots(transaction);
        if (current == null || this.amount == 0) {
            this.variant = incoming;
        } else if (!current.equals(incoming)) {
            this.variant = policy.merge(current, this.amount, incoming, inserted);
        }
        this.amount += inserted;
        return inserted;
    }

    @Override
    public long extract(@NotNull ResourceVariant requested, long maxAmount, @NotNull TransactionContext transaction) {
        requireNonNegative(maxAmount);
        if (!this.extraction || maxAmount == 0 || this.amount == 0 || !requested.equals(this.variant)) return 0;
        long extracted = Math.min(maxAmount, this.amount);
        updateSnapshots(transaction);
        this.amount -= extracted;
        if (this.amount == 0) this.variant = null;
        return extracted;
    }

    @Override
    public @NotNull List<StorageView> views() {
        return List.of(view());
    }

    public @NotNull StorageView view() {
        return new StorageView(this.variant, this.amount, capacity());
    }

    @Override
    public boolean supportsInsertion() {
        return this.insertion;
    }

    @Override
    public boolean supportsExtraction() {
        return this.extraction;
    }

    public @Nullable ResourceVariant variant() {
        return this.variant;
    }

    public long amount() {
        return this.amount;
    }

    /** The most this storage holds; subclasses may change it at runtime (e.g. upgrades). */
    public long capacity() {
        return this.capacity;
    }

    public boolean isEmpty() {
        return this.amount == 0;
    }

    /**
     * Replaces the contents outside of any transaction. For loading persisted state only.
     */
    public void load(@Nullable ResourceVariant variant, long amount) {
        if (amount < 0 || amount > this.capacity) {
            throw new IllegalArgumentException("Amount " + amount + " is outside 0.." + this.capacity);
        }
        this.variant = amount == 0 ? null : Objects.requireNonNull(variant, "variant");
        this.amount = amount;
    }

    @Override
    protected @NotNull Snapshot createSnapshot() {
        return new Snapshot(this.variant, this.amount);
    }

    @Override
    protected void readSnapshot(@NotNull Snapshot snapshot) {
        this.variant = snapshot.variant();
        this.amount = snapshot.amount();
    }

    @Override
    public String toString() {
        return "SingleVariantStorage[" + (this.amount == 0 ? "empty" : this.amount + " " + this.variant) + "/" + this.capacity + "]";
    }

    private static void requireNonNegative(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must not be negative: " + amount);
    }

    public record Snapshot(@Nullable ResourceVariant variant, long amount) {
    }

    public static class Builder {
        private final long capacity;
        private Predicate<ResourceVariant> filter = variant -> true;
        private boolean insertion = true;
        private boolean extraction = true;

        protected Builder(long capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive: " + capacity);
            this.capacity = capacity;
        }

        /** Only variants passing {@code filter} are accepted. */
        public @NotNull Builder filter(@NotNull Predicate<ResourceVariant> filter) {
            this.filter = Objects.requireNonNull(filter);
            return this;
        }

        public @NotNull Builder insertOnly() {
            this.extraction = false;
            return this;
        }

        public @NotNull Builder extractOnly() {
            this.insertion = false;
            return this;
        }

        public @NotNull SingleVariantStorage build() {
            return new SingleVariantStorage(this);
        }
    }
}
