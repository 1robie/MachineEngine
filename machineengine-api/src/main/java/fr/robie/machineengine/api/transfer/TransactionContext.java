package fr.robie.machineengine.api.transfer;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The transaction an operation runs in (ARCHITECTURE.md §10.2, D11). Operations take a context
 * rather than a {@link Transaction} so they cannot commit or abort it themselves.
 *
 * <p>A transaction and everything done inside it belongs to the thread that opened it.</p>
 */
@ApiStatus.NonExtendable
public interface TransactionContext {

    /** 0 for the outer transaction, 1 for its first nested transaction, … */
    int nestingDepth();

    /** @return the enclosing transaction, or {@code null} for the outer one */
    @Nullable TransactionContext parent();

    /**
     * Opens a transaction nested in this one. Its changes are undone if it aborts, and become part
     * of this transaction if it commits.
     *
     * @throws IllegalStateException if this is not the innermost open transaction
     */
    @NotNull Transaction openNested();

    /** Called when this transaction closes, in reverse registration order. */
    void addCloseCallback(@NotNull CloseCallback callback);

    /**
     * Runs {@code action} once the outer transaction has committed, if every transaction between
     * this one and the outer one committed too. Dropped on any abort. Use it for side effects such
     * as wake-ups, events and dirty marking.
     */
    void onCommit(@NotNull Runnable action);

    @FunctionalInterface
    interface CloseCallback {
        void onClose(@NotNull TransactionContext transaction, @NotNull Outcome outcome);
    }

    enum Outcome {
        COMMITTED,
        ABORTED;

        public boolean wasAborted() {
            return this == ABORTED;
        }
    }
}
