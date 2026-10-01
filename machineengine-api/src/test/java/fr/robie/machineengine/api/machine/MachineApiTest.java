package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.upgrade.Modifier;
import fr.robie.machineengine.api.upgrade.ModifierType;
import fr.robie.machineengine.api.upgrade.Modifiers;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineApiTest {
    private static final ModifierType SPEED = ModifierType.nonNegative(Key.key("test", "speed"));
    private static final ModifierType OTHER = ModifierType.nonNegative(Key.key("test", "other"));

    @Test
    void modifiersStackLikeMinecraftAttributes() {
        List<Modifier> modifiers = List.of(
                Modifier.multiplyTotal(SPEED, 500),       // ×1.5, applied last
                Modifier.add(SPEED, 20),                  // 100 + 20 = 120
                Modifier.addMultipliedBase(SPEED, 250),   // fractions add up: ×(1 + 0.25 + 0.25)
                Modifier.addMultipliedBase(SPEED, 250),
                Modifier.add(OTHER, 1_000_000));          // other types are ignored
        assertEquals(270, Modifiers.apply(SPEED, 100, modifiers), "(100 + 20) × 1.5 × 1.5");
        assertEquals(0, Modifiers.apply(SPEED, 100, List.of(Modifier.addMultipliedBase(SPEED, -2000))), "clamped at min");
        assertEquals(33, Modifiers.apply(SPEED, 100, List.of(Modifier.multiplyTotal(SPEED, -667))), "rounded down");
        assertEquals(Long.MAX_VALUE, Modifiers.apply(SPEED, Long.MAX_VALUE, List.of(Modifier.multiplyTotal(SPEED, 1000))),
                "clamped at max instead of overflowing");
    }

    @Test
    void relativeSidesFollowTheFacing() {
        assertEquals(Direction.EAST, RelativeSide.FRONT.toAbsolute(Direction.EAST));
        assertEquals(Direction.WEST, RelativeSide.BACK.toAbsolute(Direction.EAST));
        assertEquals(Direction.NORTH, RelativeSide.LEFT.toAbsolute(Direction.EAST));
        assertEquals(Direction.SOUTH, RelativeSide.RIGHT.toAbsolute(Direction.EAST));
        assertEquals(Direction.UP, RelativeSide.TOP.toAbsolute(Direction.SOUTH));
        for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            for (RelativeSide side : RelativeSide.values()) {
                assertEquals(side, RelativeSide.fromAbsolute(facing, side.toAbsolute(facing)));
            }
        }
    }

    @Test
    void sleepingStatesWakeOnlyForRelevantReasons() {
        assertTrue(SchedulerState.WAITING.wakesOn(WakeReason.RESOURCE_INSERTED));
        assertFalse(SchedulerState.WAITING.wakesOn(WakeReason.RESOURCE_EXTRACTED));
        assertTrue(SchedulerState.BLOCKED.wakesOn(WakeReason.RESOURCE_EXTRACTED));
        assertFalse(SchedulerState.BLOCKED.wakesOn(WakeReason.RESOURCE_INSERTED));
        assertFalse(SchedulerState.SCHEDULED.wakesOn(WakeReason.RESOURCE_INSERTED), "a running process is not interrupted");
        assertTrue(SchedulerState.SCHEDULED.wakesOn(WakeReason.TIMER));
        assertTrue(SchedulerState.SLEEPING.wakesOn(WakeReason.RESOURCE_EXTRACTED));
        assertFalse(SchedulerState.DISABLED.wakesOn(WakeReason.TIMER));
        assertFalse(SchedulerState.ACTIVE.wakesOn(WakeReason.TIMER));
        WakeReason moon = WakeReason.external(Key.key("magic", "full_moon"));
        assertTrue(SchedulerState.WAITING.wakesOn(moon));
        assertThrows(IllegalArgumentException.class, () -> WakeReason.external(Key.key("machineengine", "fake")));
    }
}
