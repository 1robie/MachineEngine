package fr.robie.machineengine.api.event;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A registered kind of event (ARCHITECTURE.md §26). Addons may register their own types.
 *
 * @param <E> the event object posted for this type
 */
public final class EventType<E> implements Keyed {
    private final Key key;
    private final Class<E> eventClass;

    private EventType(Key key, Class<E> eventClass) {
        this.key = key;
        this.eventClass = eventClass;
    }

    public static <E> @NotNull EventType<E> of(@NotNull Key key, @NotNull Class<E> eventClass) {
        return new EventType<>(Objects.requireNonNull(key), Objects.requireNonNull(eventClass));
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull Class<E> eventClass() {
        return this.eventClass;
    }

    /** Whether listeners may cancel events of this type in the {@link EventPhase#PRE} phase. */
    public boolean isCancellable() {
        return Cancellable.class.isAssignableFrom(this.eventClass);
    }

    @Override
    public String toString() {
        return "EventType[" + this.key.asString() + "]";
    }
}
