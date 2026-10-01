package fr.robie.machineengine.api.transfer;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Opens outer transactions. Each thread has its own transaction stack. */
@ApiStatus.NonExtendable
public interface TransactionManager {

    /**
     * @throws IllegalStateException if the current thread already has an open transaction; open a
     *         nested one from it instead
     */
    @NotNull Transaction openOuter();

    /** @return whether the current thread has an open transaction */
    boolean isOpen();

    /** @return the current thread's innermost open transaction, or {@code null} */
    @Nullable TransactionContext current();
}
