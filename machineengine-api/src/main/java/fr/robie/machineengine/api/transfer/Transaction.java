package fr.robie.machineengine.api.transfer;

import org.jetbrains.annotations.ApiStatus;

/**
 * An open transaction. Use try-with-resources: closing without {@link #commit()} aborts.
 *
 * <pre>{@code
 * try (Transaction tx = engine.transactions().openOuter()) {
 *     long moved = Transfers.move(source, target, filter, 1000, tx).moved();
 *     if (moved == 1000) tx.commit();
 * }
 * }</pre>
 *
 * <p>Simulation is a transaction that is aborted, so simulating and executing share one code path.</p>
 */
@ApiStatus.NonExtendable
public interface Transaction extends TransactionContext, AutoCloseable {

    /**
     * @throws IllegalStateException if already closed or a nested transaction is still open
     */
    void commit();

    /**
     * @throws IllegalStateException if already closed or a nested transaction is still open
     */
    void abort();

    boolean isOpen();

    /** Aborts if still open. */
    @Override
    void close();
}
