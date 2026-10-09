package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.machine.MachineAction;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.robie.machineengine.test.MachineFixtures.A;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Menu actions and lists a machine type declares (D40). */
class MachineActionTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final AddonId TEST = new AddonId("test");
    private static final UUID PLAYER = UUID.randomUUID();

    private static final MachineDataKey<Long> STORED = MachineDataKey.of("stored",
            VersionedCodec.builder(Key.key("omega", "stored"), Codecs.LONG).build(), 0L);

    /** Holds up to 10 of A as a number: "put" takes from the cursor, "take" gives one back, "list" shows it. */
    private static final MachineType BANK = MachineType.builder(Key.key("omega", "bank"))
            .data(STORED)
            .action("put", (context, click) -> {
                if (click.held() == null || !click.held().variant().equals(A.variant())) {
                    return MachineAction.Outcome.refused(Component.text("Only A"));
                }
                long room = 10 - context.get(STORED);
                long used = Math.min(room, click.kind() == MachineAction.ClickKind.RIGHT ? 1 : click.held().amount());
                if (used <= 0) return MachineAction.Outcome.refused(Component.text("Full"));
                context.set(STORED, context.get(STORED) + used);
                return MachineAction.Outcome.used(used);
            })
            .action("take", (context, click) -> {
                long stored = context.get(STORED);
                if (stored == 0) return MachineAction.Outcome.nothing();
                long taken = click.kind() == MachineAction.ClickKind.SHIFT ? stored : 1;
                context.set(STORED, stored - taken);
                return MachineAction.Outcome.give(A.stack(taken));
            })
            .action("broken", (context, click) -> {
                throw new IllegalStateException("boom");
            })
            .list("units", context -> {
                List<Map<String, String>> rows = new ArrayList<>();
                for (long i = 0; i < context.get(STORED); i++) rows.add(Map.of("number", Long.toString(i + 1)));
                return rows;
            })
            .behavior(type -> MachineContext::sleep)
            .build();

    private static MachineInstanceImpl bank(CoreMachineEngine engine) {
        return FakeWorld.singleRegion(engine).place(BANK, HERE, Direction.NORTH);
    }

    private static CoreMachineEngine engine() {
        return TestEngine.builder()
                .addon(MachineFixtures.alpha())
                .addon("omega", ctx -> ctx.machineTypes().register(BANK))
                .start();
    }

    private static MachineAction.Click click(MachineAction.ClickKind kind, ResourceStack held) {
        return new MachineAction.Click(PLAYER, kind, 0, held);
    }

    @Test
    void actionsChangeTheMachineAndSayWhatTheyUsedAndGave() {
        MachineInstanceImpl bank = bank(engine());

        MachineAction.Outcome put = bank.runAction("put", click(MachineAction.ClickKind.LEFT, A.stack(64)));
        assertTrue(put.done());
        assertEquals(10, put.used(), "only what fits comes off the cursor");
        assertEquals(10L, bank.context().get(STORED));

        MachineAction.Outcome full = bank.runAction("put", click(MachineAction.ClickKind.LEFT, A.stack(1)));
        assertFalse(full.done());
        assertEquals(0, full.used());
        assertNotNull(full.message(), "a refusal says why");

        MachineAction.Outcome one = bank.runAction("take", click(MachineAction.ClickKind.LEFT, null));
        assertEquals(List.of(A.stack(1)), one.give());
        MachineAction.Outcome all = bank.runAction("take", click(MachineAction.ClickKind.SHIFT, null));
        assertEquals(List.of(A.stack(9)), all.give());
        assertFalse(bank.runAction("take", click(MachineAction.ClickKind.LEFT, null)).done(), "nothing left");
    }

    @Test
    void unknownAndFailingActionsAreRefused() {
        MachineInstanceImpl bank = bank(engine());
        MachineAction.Outcome unknown = bank.runAction("nope", click(MachineAction.ClickKind.LEFT, null));
        assertFalse(unknown.done());
        assertNotNull(unknown.message());
        MachineAction.Outcome broken = bank.runAction("broken", click(MachineAction.ClickKind.LEFT, null));
        assertFalse(broken.done(), "an action that throws changes nothing visible and is reported");
        assertNotNull(broken.message());
    }

    @Test
    void listsShowRowsFromTheMachine() {
        MachineInstanceImpl bank = bank(engine());
        assertEquals(List.of(), bank.list("units"));
        bank.runAction("put", click(MachineAction.ClickKind.LEFT, A.stack(3)));
        assertEquals(List.of(Map.of("number", "1"), Map.of("number", "2"), Map.of("number", "3")), bank.list("units"));
        assertEquals(List.of(), bank.list("nope"), "an unknown list is empty");
    }

    @Test
    void actionsThatDidSomethingArePosted() {
        CoreMachineEngine engine = engine();
        MachineInstanceImpl bank = bank(engine);
        List<MachineEvents.Action> events = new ArrayList<>();
        engine.events().subscribe(TEST, MachineEvents.ACTION, EventPhase.POST, events::add);

        bank.runAction("put", click(MachineAction.ClickKind.RIGHT, A.stack(5)));
        bank.runAction("take", click(MachineAction.ClickKind.SHIFT, null));
        bank.runAction("take", click(MachineAction.ClickKind.LEFT, null));

        assertEquals(List.of("put", "take"), events.stream().map(MachineEvents.Action::action).toList(), "refused runs post nothing");
        assertEquals(1, events.getFirst().outcome().used());
    }

    @Test
    void idsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> MachineType.builder(Key.key("omega", "bad")).action("Take-All", (c, k) -> null));
        assertThrows(IllegalArgumentException.class, () -> MachineType.builder(Key.key("omega", "bad")).list("a b", c -> List.of()));
    }
}
