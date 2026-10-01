package fr.robie.machineengine.core.event;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.event.EventPriority;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.event.Subscription;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Copy-on-write event bus: posting reads an immutable listener array and never locks; subscribing
 * is rare and synchronized.
 */
public final class CoreEventBus implements EventBus {
    private static final Comparator<Listener<?>> ORDER =
            Comparator.<Listener<?>, EventPriority>comparing(listener -> listener.priority).thenComparingLong(listener -> listener.sequence);

    private final Map<EventType<?>, EnumMap<EventPhase, Listener<?>[]>> listeners = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Logger logger;

    public CoreEventBus(@NotNull Logger logger) {
        this.logger = logger;
    }

    @Override
    public synchronized <E> @NotNull Subscription subscribe(@NotNull AddonId owner, @NotNull EventType<E> type,
                                                            @NotNull EventPhase phase, @NotNull EventPriority priority,
                                                            @NotNull Consumer<? super E> listener) {
        Listener<E> entry = new Listener<>(this, owner, type, phase, priority, this.sequence.getAndIncrement(), listener);
        EnumMap<EventPhase, Listener<?>[]> byPhase = new EnumMap<>(EventPhase.class);
        EnumMap<EventPhase, Listener<?>[]> existing = this.listeners.get(type);
        if (existing != null) byPhase.putAll(existing);
        List<Listener<?>> list = new ArrayList<>(List.of(byPhase.getOrDefault(phase, new Listener<?>[0])));
        list.add(entry);
        list.sort(ORDER);
        byPhase.put(phase, list.toArray(Listener<?>[]::new));
        this.listeners.put(type, byPhase);
        return entry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E> @NotNull E post(@NotNull EventType<E> type, @NotNull EventPhase phase, @NotNull E event) {
        if (!type.eventClass().isInstance(event)) {
            throw new IllegalArgumentException(event.getClass().getName() + " is not a " + type.eventClass().getName()
                    + " (event type " + type.key().asString() + ")");
        }
        EnumMap<EventPhase, Listener<?>[]> byPhase = this.listeners.get(type);
        if (byPhase == null) return event;
        Listener<?>[] array = byPhase.get(phase);
        if (array == null) return event;
        for (Listener<?> listener : array) {
            try {
                ((Listener<E>) listener).consumer.accept(event);
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "Listener of " + listener.owner + " failed on " + type.key().asString()
                        + " (" + phase + ")", e);
            }
        }
        return event;
    }

    @Override
    public boolean hasListeners(@NotNull EventType<?> type, @NotNull EventPhase phase) {
        EnumMap<EventPhase, Listener<?>[]> byPhase = this.listeners.get(type);
        if (byPhase == null) return false;
        Listener<?>[] array = byPhase.get(phase);
        return array != null && array.length > 0;
    }

    @Override
    public synchronized void unsubscribeAll(@NotNull AddonId owner) {
        for (EventType<?> type : List.copyOf(this.listeners.keySet())) {
            EnumMap<EventPhase, Listener<?>[]> byPhase = new EnumMap<>(this.listeners.get(type));
            byPhase.replaceAll((phase, array) -> java.util.Arrays.stream(array)
                    .filter(listener -> {
                        if (!listener.owner.equals(owner)) return true;
                        listener.active = false;
                        return false;
                    })
                    .toArray(Listener<?>[]::new));
            this.listeners.put(type, byPhase);
        }
    }

    private synchronized void remove(Listener<?> listener) {
        EnumMap<EventPhase, Listener<?>[]> existing = this.listeners.get(listener.type);
        if (existing == null) return;
        EnumMap<EventPhase, Listener<?>[]> byPhase = new EnumMap<>(existing);
        Listener<?>[] array = byPhase.get(listener.phase);
        if (array == null) return;
        byPhase.put(listener.phase, java.util.Arrays.stream(array).filter(other -> other != listener).toArray(Listener<?>[]::new));
        this.listeners.put(listener.type, byPhase);
    }

    private static final class Listener<E> implements Subscription {
        private final CoreEventBus bus;
        private final AddonId owner;
        private final EventType<E> type;
        private final EventPhase phase;
        private final EventPriority priority;
        private final long sequence;
        private final Consumer<? super E> consumer;
        private volatile boolean active = true;

        private Listener(CoreEventBus bus, AddonId owner, EventType<E> type, EventPhase phase, EventPriority priority,
                         long sequence, Consumer<? super E> consumer) {
            this.bus = bus;
            this.owner = owner;
            this.type = type;
            this.phase = phase;
            this.priority = priority;
            this.sequence = sequence;
            this.consumer = consumer;
        }

        @Override
        public void unsubscribe() {
            if (!this.active) return;
            this.active = false;
            this.bus.remove(this);
        }

        @Override
        public boolean isActive() {
            return this.active;
        }
    }
}
