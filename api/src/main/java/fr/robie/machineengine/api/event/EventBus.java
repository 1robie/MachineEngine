package fr.robie.machineengine.api.event;

import fr.robie.machineengine.api.addon.AddonId;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * MachineEngine's own event bus (D12). Events run on the thread performing the operation;
 * listeners must not block. A failing listener is logged and does not stop the others.
 */
@ApiStatus.NonExtendable
public interface EventBus {

    <E> @NotNull Subscription subscribe(@NotNull AddonId owner, @NotNull EventType<E> type, @NotNull EventPhase phase,
                                        @NotNull EventPriority priority, @NotNull Consumer<? super E> listener);

    default <E> @NotNull Subscription subscribe(@NotNull AddonId owner, @NotNull EventType<E> type,
                                                @NotNull EventPhase phase, @NotNull Consumer<? super E> listener) {
        return subscribe(owner, type, phase, EventPriority.NORMAL, listener);
    }

    /**
     * Delivers {@code event} to the listeners of {@code type} in {@code phase}.
     *
     * @return {@code event}, for reading the listeners' changes (e.g. cancellation)
     */
    <E> @NotNull E post(@NotNull EventType<E> type, @NotNull EventPhase phase, @NotNull E event);

    /** Lets callers skip building events nobody listens to (§26). */
    boolean hasListeners(@NotNull EventType<?> type, @NotNull EventPhase phase);

    /** Removes every listener of {@code owner}, e.g. when the addon disables. */
    void unsubscribeAll(@NotNull AddonId owner);
}
