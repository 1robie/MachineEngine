package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.SnapshotParticipant;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.TransactionContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The resource handler other machines see through a port (§11.2): enforces direction, filter and the
 * per-tick rate. The rate counter takes part in transactions, so aborted transfers do not use it up.
 */
final class PortHandler extends SnapshotParticipant<PortHandler.Usage> implements ResourceHandler {
    private final PortDefinition definition;
    private final MachineStorage storage;
    private final Supplier<ResourceTags> tags;
    private final LongSupplier gameTime;
    private final LongSupplier rate;
    private long usageTick = Long.MIN_VALUE;
    private long used;

    PortHandler(PortDefinition definition, MachineStorage storage, Supplier<ResourceTags> tags, LongSupplier gameTime,
                LongSupplier rate) {
        this.definition = definition;
        this.storage = storage;
        this.tags = tags;
        this.gameTime = gameTime;
        this.rate = rate;
    }

    PortDefinition definition() {
        return this.definition;
    }

    @Override
    public long insert(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        if (!this.definition.direction().allowsInsertion()) return 0;
        long allowed = allowed(variant, maxAmount);
        if (allowed == 0) return 0;
        return consume(this.storage.insert(variant, allowed, transaction), transaction);
    }

    @Override
    public long extract(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        if (!this.definition.direction().allowsExtraction()) return 0;
        long allowed = allowed(variant, maxAmount);
        if (allowed == 0) return 0;
        return consume(this.storage.extract(variant, allowed, transaction), transaction);
    }

    @Override
    public @NotNull List<StorageView> views() {
        return this.storage.views();
    }

    @Override
    public boolean supportsInsertion() {
        return this.definition.direction().allowsInsertion();
    }

    @Override
    public boolean supportsExtraction() {
        return this.definition.direction().allowsExtraction();
    }

    private long allowed(ResourceVariant variant, long maxAmount) {
        if (maxAmount < 0) throw new IllegalArgumentException("Amount must not be negative: " + maxAmount);
        if (!this.definition.filter().test(variant, this.tags.get())) return 0;
        long rate = this.rate.getAsLong();
        if (rate == Long.MAX_VALUE) return maxAmount;
        return Math.min(maxAmount, Math.max(0, rate - usedNow()));
    }

    private long consume(long moved, TransactionContext transaction) {
        if (moved > 0 && this.rate.getAsLong() != Long.MAX_VALUE) {
            updateSnapshots(transaction);
            this.used = usedNow() + moved;
            this.usageTick = this.gameTime.getAsLong();
        }
        return moved;
    }

    private long usedNow() {
        return this.usageTick == this.gameTime.getAsLong() ? this.used : 0;
    }

    @Override
    protected @NotNull Usage createSnapshot() {
        return new Usage(this.usageTick, this.used);
    }

    @Override
    protected void readSnapshot(@NotNull Usage snapshot) {
        this.usageTick = snapshot.tick();
        this.used = snapshot.used();
    }

    record Usage(long tick, long used) {
    }
}
