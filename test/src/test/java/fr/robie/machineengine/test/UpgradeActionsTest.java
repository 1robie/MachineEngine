package fr.robie.machineengine.test;

import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.machine.MachineAction;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.upgrade.UpgradeActions;
import fr.robie.machineengine.api.upgrade.UpgradeItems;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The upgrade action and list every machine with upgrade slots gets (D40). */
class UpgradeActionsTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final UUID PLAYER = UUID.randomUUID();

    private static final UpgradeType SPEED = UpgradeType.of(Key.key("kappa", "speed"), 4);
    private static final UpgradeType MUFFLER = UpgradeType.of(Key.key("kappa", "muffler"), 1);
    private static final UpgradeType ANCHOR = UpgradeType.of(Key.key("kappa", "anchor"), 1);
    /** Not taken by the press. */
    private static final UpgradeType FILTER = UpgradeType.of(Key.key("kappa", "filter"), 1);

    /** Two upgrade slots; takes everything but the filter. */
    private static final MachineType PRESS = MachineType.builder(Key.key("kappa", "press"))
            .upgradeSlots(2, upgrade -> !upgrade.equals(FILTER))
            .behavior(type -> MachineContext::sleep)
            .build();
    private static final MachineType PLAIN = MachineType.builder(Key.key("kappa", "plain"))
            .behavior(type -> MachineContext::sleep)
            .build();

    /** The speed upgrade's item has its own id (the behavior names the upgrade); the others are named after their upgrade (the old rule). */
    private static final Key SPEED_CHIP = Key.key("kappa", "speed_chip");

    private final List<Key> fallbacks = new ArrayList<>();

    @BeforeEach
    void installItems() {
        UpgradeItems.setAccess(new UpgradeItems.Access() {
            @Override
            public @Nullable Key upgradeOf(@NotNull ResourceVariant item) {
                ItemData data = item.components().get(Items.DATA);
                return data != null && data.item().equals(SPEED_CHIP) ? SPEED.key() : null;
            }

            @Override
            public @Nullable ResourceVariant itemOf(@NotNull Key upgrade) {
                if (upgrade.equals(SPEED.key())) return item(SPEED_CHIP);
                return upgrade.equals(ANCHOR.key()) ? null : item(upgrade);
            }

            @Override
            public void usedIdFallback(@NotNull Key item) {
                UpgradeActionsTest.this.fallbacks.add(item);
            }
        });
    }

    @AfterEach
    void removeItems() {
        UpgradeItems.setAccess(null);
    }

    private static ResourceVariant item(Key id) {
        return Items.variant(new ItemData(id, new byte[0]));
    }

    private static MachineInstanceImpl press() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("kappa", ctx -> {
                    List.of(SPEED, MUFFLER, ANCHOR, FILTER).forEach(ctx.upgradeTypes()::register);
                    ctx.machineTypes().register(PRESS);
                    ctx.machineTypes().register(PLAIN);
                })
                .start();
        return FakeWorld.singleRegion(engine).place(PRESS, HERE, Direction.NORTH);
    }

    private static MachineAction.Outcome click(MachineInstanceImpl machine, MachineAction.ClickKind kind, int index, @Nullable ResourceStack held) {
        return machine.runAction(UpgradeActions.ACTION, new MachineAction.Click(PLAYER, kind, index, held));
    }

    private static int count(MachineInstanceImpl machine, UpgradeType upgrade) {
        return machine.upgrades().stream().filter(u -> u.type().equals(upgrade)).mapToInt(u -> u.count()).sum();
    }

    @Test
    void onlyTypesWithUpgradeSlotsGetTheAction() {
        assertTrue(PRESS.action(UpgradeActions.ACTION).isPresent());
        assertTrue(PRESS.list(UpgradeActions.LIST).isPresent());
        assertTrue(PLAIN.action(UpgradeActions.ACTION).isEmpty());
        MachineType own = MachineType.builder(Key.key("kappa", "own"))
                .upgradeSlots(1, upgrade -> true)
                .action(UpgradeActions.ACTION, (context, click) -> MachineAction.Outcome.nothing())
                .behavior(type -> MachineContext::sleep)
                .build();
        assertTrue(own.action(UpgradeActions.ACTION).orElseThrow() != UpgradeActions.UPGRADE, "a type's own action wins");
    }

    @Test
    void installsFromAStackAndSaysWhatItUsed() {
        MachineInstanceImpl press = press();
        MachineAction.Outcome one = click(press, MachineAction.ClickKind.RIGHT, 0, item(SPEED_CHIP).stack(10));
        assertEquals(1, one.used(), "right-click: one");
        MachineAction.Outcome rest = click(press, MachineAction.ClickKind.LEFT, 0, item(SPEED_CHIP).stack(10));
        assertEquals(3, rest.used(), "only what fits (max 4)");
        assertEquals(4, count(press, SPEED));
        assertTrue(this.fallbacks.isEmpty(), "the behavior named it");

        assertEquals(1, click(press, MachineAction.ClickKind.INSERT, 0, item(MUFFLER.key()).stack(1)).used(),
                "a shift-click from the inventory installs too");
        assertEquals(List.of(MUFFLER.key()), this.fallbacks, "the id rule is reported");
    }

    @Test
    void refusalsSayWhy() {
        MachineInstanceImpl press = press();
        assertRefused(click(press, MachineAction.ClickKind.LEFT, 0, item(Key.key("kappa", "dirt")).stack(1)), "not an upgrade");
        assertRefused(click(press, MachineAction.ClickKind.LEFT, 0, item(FILTER.key()).stack(1)), "not accepted");
        click(press, MachineAction.ClickKind.LEFT, 0, item(SPEED_CHIP).stack(4));
        assertRefused(click(press, MachineAction.ClickKind.LEFT, 0, item(SPEED_CHIP).stack(1)), "full");
        click(press, MachineAction.ClickKind.LEFT, 0, item(MUFFLER.key()).stack(1));
        assertRefused(click(press, MachineAction.ClickKind.LEFT, 0, item(ANCHOR.key()).stack(1)), "both slots used");
        assertTrue(press.upgradeRoom(ANCHOR).isErr());
        assertTrue(press.upgradeRoom(MUFFLER).isErr(), "muffler at its max");
        assertEquals(1, press.upgradeRoom(SPEED).isOk() ? 0 : 1, "speed full too");
    }

    private static void assertRefused(MachineAction.Outcome outcome, String why) {
        assertFalse(outcome.done(), why);
        assertEquals(0, outcome.used(), why);
        assertNotNull(outcome.message(), why);
    }

    @Test
    void listsAcceptedUpgradesAndTakesThemOutByRow() {
        MachineInstanceImpl press = press();
        click(press, MachineAction.ClickKind.LEFT, 0, item(SPEED_CHIP).stack(3));
        List<Map<String, String>> rows = press.list(UpgradeActions.LIST);
        assertEquals(List.of("kappa:speed", "kappa:muffler", "kappa:anchor"), rows.stream().map(row -> row.get("id")).toList(),
                "accepted upgrades in registry order, not the filter");
        assertEquals("3", rows.getFirst().get("count"));
        assertEquals("4", rows.getFirst().get("max"));
        assertEquals("true", rows.getFirst().get("installed"));
        assertEquals("kappa:speed_chip", rows.getFirst().get("item"));
        assertEquals("", rows.get(2).get("item"), "the anchor has no item");

        MachineAction.Outcome one = click(press, MachineAction.ClickKind.LEFT, 0, null);
        assertEquals(List.of(item(SPEED_CHIP).stack(1)), one.give());
        MachineAction.Outcome all = click(press, MachineAction.ClickKind.SHIFT, 0, null);
        assertEquals(List.of(item(SPEED_CHIP).stack(2)), all.give());
        assertEquals(0, count(press, SPEED));
        assertFalse(click(press, MachineAction.ClickKind.LEFT, 0, null).done(), "nothing left in that row");
        assertFalse(click(press, MachineAction.ClickKind.LEFT, 9, null).done(), "no such row");
    }

    @Test
    void anUpgradeWithoutAnItemStaysIn() {
        MachineInstanceImpl press = press();
        assertTrue(press.installUpgrade(ANCHOR, 1).isOk());
        MachineAction.Outcome take = click(press, MachineAction.ClickKind.LEFT, 2, null);
        assertFalse(take.done());
        assertNotNull(take.message());
        assertEquals(1, count(press, ANCHOR), "never removed without an item to give back");
    }
}
