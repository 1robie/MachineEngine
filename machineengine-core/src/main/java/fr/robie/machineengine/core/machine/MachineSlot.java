package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.api.transfer.storage.SingleVariantStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * A machine storage slot. Reports committed changes to its machine (dirty marking and wake-ups) and
 * can be <em>locked</em> around the raw data of a resource type that is not registered (D3): a locked
 * slot accepts and gives nothing, and its data is written back unchanged.
 */
public final class MachineSlot extends SingleVariantStorage {
    private final Listener listener;
    private final java.util.function.LongSupplier capacity;
    private @Nullable DataValue lockedData;

    MachineSlot(long capacity, @NotNull Predicate<ResourceVariant> filter, @NotNull Listener listener) {
        this(() -> capacity, filter, listener);
    }

    /** A slot whose capacity can change at runtime (upgrades). */
    MachineSlot(@NotNull java.util.function.LongSupplier capacity, @NotNull Predicate<ResourceVariant> filter, @NotNull Listener listener) {
        super(SingleVariantStorage.builder(Long.MAX_VALUE).filter(filter));
        this.capacity = capacity;
        this.listener = listener;
    }

    @Override
    public long capacity() {
        return this.capacity.getAsLong();
    }

    @Override
    public long insert(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        if (this.lockedData != null) return 0;
        long inserted = super.insert(variant, maxAmount, transaction);
        if (inserted > 0) transaction.onCommit(() -> this.listener.inserted(variant, inserted));
        return inserted;
    }

    @Override
    public long extract(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        if (this.lockedData != null) return 0;
        long extracted = super.extract(variant, maxAmount, transaction);
        if (extracted > 0) transaction.onCommit(() -> this.listener.extracted(variant, extracted));
        return extracted;
    }

    @Override
    public @NotNull List<StorageView> views() {
        return this.lockedData == null ? super.views() : List.of(new StorageView(null, 0, capacity()));
    }

    public @Nullable DataValue lockedData() {
        return this.lockedData;
    }

    /** Drops the data of an unregistered resource; the slot is usable again. */
    void unlock() {
        this.lockedData = null;
    }

    void lock(@NotNull DataValue data) {
        load(null, 0);
        this.lockedData = data;
    }

    interface Listener {
        void inserted(@NotNull ResourceVariant variant, long amount);

        void extracted(@NotNull ResourceVariant variant, long amount);
    }
}
