package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.event.Cancellable;
import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.event.EventPriority;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.event.Subscription;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {
    private static final AddonId A = new AddonId("a");
    private static final AddonId B = new AddonId("b");
    private static final EventType<Boom> BOOM = EventType.of(Key.key("test", "boom"), Boom.class);

    private final EventBus bus = TestEngine.builder().start().events();

    @Test
    void listenersRunByPriorityThenSubscriptionOrderPerPhase() {
        List<String> calls = new ArrayList<>();
        this.bus.subscribe(A, BOOM, EventPhase.PRE, EventPriority.LATE, event -> calls.add("late"));
        this.bus.subscribe(A, BOOM, EventPhase.PRE, EventPriority.FIRST, event -> calls.add("first"));
        this.bus.subscribe(B, BOOM, EventPhase.PRE, EventPriority.NORMAL, event -> calls.add("normal-1"));
        this.bus.subscribe(A, BOOM, EventPhase.PRE, EventPriority.NORMAL, event -> calls.add("normal-2"));
        this.bus.subscribe(A, BOOM, EventPhase.POST, event -> calls.add("post"));

        this.bus.post(BOOM, EventPhase.PRE, new Boom());
        assertEquals(List.of("first", "normal-1", "normal-2", "late"), calls);
    }

    @Test
    void cancellationIsVisibleToTheCallerAndLaterListeners() {
        List<Boolean> seen = new ArrayList<>();
        this.bus.subscribe(A, BOOM, EventPhase.PRE, EventPriority.EARLY, event -> event.setCancelled(true));
        this.bus.subscribe(B, BOOM, EventPhase.PRE, EventPriority.LATE, event -> seen.add(event.isCancelled()));

        assertTrue(this.bus.post(BOOM, EventPhase.PRE, new Boom()).isCancelled());
        assertEquals(List.of(true), seen);
        assertTrue(BOOM.isCancellable());
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() {
        List<String> calls = new ArrayList<>();
        this.bus.subscribe(A, BOOM, EventPhase.POST, EventPriority.FIRST, event -> {
            throw new IllegalStateException("listener bug");
        });
        this.bus.subscribe(B, BOOM, EventPhase.POST, event -> calls.add("still called"));

        this.bus.post(BOOM, EventPhase.POST, new Boom());
        assertEquals(List.of("still called"), calls);
    }

    @Test
    void unsubscribing() {
        List<String> calls = new ArrayList<>();
        Subscription single = this.bus.subscribe(A, BOOM, EventPhase.POST, event -> calls.add("single"));
        this.bus.subscribe(B, BOOM, EventPhase.POST, event -> calls.add("b-1"));
        Subscription second = this.bus.subscribe(B, BOOM, EventPhase.POST, event -> calls.add("b-2"));

        single.unsubscribe();
        this.bus.unsubscribeAll(B);
        this.bus.post(BOOM, EventPhase.POST, new Boom());

        assertTrue(calls.isEmpty());
        assertFalse(single.isActive());
        assertFalse(second.isActive());
        assertFalse(this.bus.hasListeners(BOOM, EventPhase.POST));
    }

    @Test
    void theEventMustMatchItsType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        EventType<Object> raw = (EventType) BOOM;
        assertThrows(IllegalArgumentException.class, () -> this.bus.post(raw, EventPhase.PRE, "not a boom"));
    }

    static final class Boom implements Cancellable {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
