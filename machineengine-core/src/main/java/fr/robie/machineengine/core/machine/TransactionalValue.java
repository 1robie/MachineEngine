package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.transfer.SnapshotParticipant;
import fr.robie.machineengine.api.transfer.TransactionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * An immutable value that changes inside transactions: rolled back on abort, so machine state such
 * as the process slot or escrow always matches the resources that were actually moved.
 */
final class TransactionalValue<T> extends SnapshotParticipant<Optional<T>> {
    private @Nullable T value;
    private final Runnable onCommit;

    TransactionalValue(@Nullable T initial, @NotNull Runnable onCommit) {
        this.value = initial;
        this.onCommit = onCommit;
    }

    @Nullable T get() {
        return this.value;
    }

    void set(@Nullable T value, @NotNull TransactionContext transaction) {
        updateSnapshots(transaction);
        this.value = value;
    }

    /** Outside any transaction: loading persisted state only. */
    void load(@Nullable T value) {
        this.value = value;
    }

    @Override
    protected @NotNull Optional<T> createSnapshot() {
        return Optional.ofNullable(this.value);
    }

    @Override
    protected void readSnapshot(@NotNull Optional<T> snapshot) {
        this.value = snapshot.orElse(null);
    }

    @Override
    protected void onFinalCommit() {
        this.onCommit.run();
    }
}
