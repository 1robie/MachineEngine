package fr.robie.machineengine.core.transfer;

import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.api.transfer.TransactionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Nested snapshot/rollback transactions (D11). Each thread has its own stack; a transaction may only
 * be used by the thread that opened it.
 */
public final class CoreTransactionManager implements TransactionManager {
    private final ThreadLocal<List<TransactionImpl>> stacks = ThreadLocal.withInitial(ArrayList::new);
    private final Logger logger;

    public CoreTransactionManager(@NotNull Logger logger) {
        this.logger = logger;
    }

    @Override
    public @NotNull Transaction openOuter() {
        List<TransactionImpl> stack = this.stacks.get();
        if (!stack.isEmpty()) {
            throw new IllegalStateException("A transaction is already open on thread " + Thread.currentThread().getName()
                    + "; open a nested transaction from it instead");
        }
        TransactionImpl transaction = new TransactionImpl(stack, null, 0);
        stack.add(transaction);
        return transaction;
    }

    @Override
    public boolean isOpen() {
        return !this.stacks.get().isEmpty();
    }

    @Override
    public @Nullable TransactionContext current() {
        List<TransactionImpl> stack = this.stacks.get();
        return stack.isEmpty() ? null : stack.getLast();
    }

    private final class TransactionImpl implements Transaction {
        private final List<TransactionImpl> stack;
        private final Thread owner = Thread.currentThread();
        private final @Nullable TransactionImpl parent;
        private final int depth;
        private final List<CloseCallback> closeCallbacks = new ArrayList<>();
        private List<Runnable> commitActions = new ArrayList<>();
        private boolean open = true;

        private TransactionImpl(List<TransactionImpl> stack, @Nullable TransactionImpl parent, int depth) {
            this.stack = stack;
            this.parent = parent;
            this.depth = depth;
        }

        @Override
        public int nestingDepth() {
            checkUsable();
            return this.depth;
        }

        @Override
        public @Nullable TransactionContext parent() {
            checkUsable();
            return this.parent;
        }

        @Override
        public @NotNull Transaction openNested() {
            checkInnermost("open a nested transaction");
            TransactionImpl nested = new TransactionImpl(this.stack, this, this.depth + 1);
            this.stack.add(nested);
            return nested;
        }

        @Override
        public void addCloseCallback(@NotNull CloseCallback callback) {
            checkUsable();
            this.closeCallbacks.add(callback);
        }

        @Override
        public void onCommit(@NotNull Runnable action) {
            checkUsable();
            this.commitActions.add(action);
        }

        @Override
        public void commit() {
            close(Outcome.COMMITTED);
        }

        @Override
        public void abort() {
            close(Outcome.ABORTED);
        }

        @Override
        public boolean isOpen() {
            return this.open;
        }

        @Override
        public void close() {
            if (this.open) abort();
        }

        private void close(Outcome outcome) {
            checkInnermost(outcome == Outcome.COMMITTED ? "commit" : "abort");
            RuntimeException failure = null;
            // Callbacks may register more callbacks on this transaction or its parent while running.
            for (int i = this.closeCallbacks.size() - 1; i >= 0; i--) {
                try {
                    this.closeCallbacks.get(i).onClose(this, outcome);
                } catch (RuntimeException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
            this.closeCallbacks.clear();
            this.open = false;
            this.stack.removeLast();

            List<Runnable> actions = this.commitActions;
            this.commitActions = List.of();
            if (outcome == Outcome.COMMITTED) {
                if (this.parent != null) {
                    this.parent.commitActions.addAll(actions);
                } else {
                    for (Runnable action : actions) {
                        try {
                            action.run();
                        } catch (RuntimeException e) {
                            CoreTransactionManager.this.logger.log(Level.SEVERE, "A transaction commit action failed", e);
                        }
                    }
                }
            }
            if (failure != null) throw failure;
        }

        private void checkUsable() {
            if (Thread.currentThread() != this.owner) {
                throw new IllegalStateException("Transaction opened on thread " + this.owner.getName()
                        + " used from thread " + Thread.currentThread().getName());
            }
            if (!this.open) throw new IllegalStateException("Transaction is closed");
        }

        private void checkInnermost(String operation) {
            checkUsable();
            if (this.stack.getLast() != this) {
                throw new IllegalStateException("Cannot " + operation + ": a nested transaction is still open");
            }
        }

        @Override
        public String toString() {
            return "Transaction[depth=" + this.depth + (this.open ? ", open" : ", closed") + "]";
        }
    }
}
