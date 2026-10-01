package fr.robie.machineengine.api.transfer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class for state that takes part in transactions (ARCHITECTURE.md §10.2). Call
 * {@link #updateSnapshots(TransactionContext)} before every change; the state is restored from the
 * snapshot if the transaction aborts.
 *
 * @param <S> an immutable snapshot of the participant's state
 */
public abstract class SnapshotParticipant<S> {
    // Index = transaction depth; null when no snapshot was taken at that depth.
    private final List<S> snapshots = new ArrayList<>();

    /** @return an immutable copy of the current state */
    protected abstract @NotNull S createSnapshot();

    /** Restores state captured by {@link #createSnapshot()}. */
    protected abstract void readSnapshot(@NotNull S snapshot);

    /** Called once the outer transaction committed changes made to this participant. */
    protected void onFinalCommit() {
    }

    /**
     * Must be called before modifying state inside {@code transaction}.
     */
    public final void updateSnapshots(@NotNull TransactionContext transaction) {
        int depth = transaction.nestingDepth();
        while (this.snapshots.size() <= depth) {
            this.snapshots.add(null);
        }
        if (this.snapshots.get(depth) == null) {
            this.snapshots.set(depth, Objects.requireNonNull(createSnapshot(), "createSnapshot() returned null"));
            transaction.addCloseCallback(this::onClose);
        }
    }

    private void onClose(TransactionContext transaction, TransactionContext.Outcome outcome) {
        int depth = transaction.nestingDepth();
        S snapshot = this.snapshots.set(depth, null);
        if (outcome.wasAborted()) {
            readSnapshot(snapshot);
            return;
        }
        TransactionContext parent = transaction.parent();
        if (parent != null) {
            // The parent may still abort: keep the oldest snapshot for it.
            if (this.snapshots.get(depth - 1) == null) {
                this.snapshots.set(depth - 1, snapshot);
                parent.addCloseCallback(this::onClose);
            }
        } else {
            transaction.onCommit(this::onFinalCommit);
        }
    }
}
