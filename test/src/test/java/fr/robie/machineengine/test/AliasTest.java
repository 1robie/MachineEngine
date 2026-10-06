package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.registry.RegistrationException;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static fr.robie.machineengine.test.DefinitionTest.map;
import static fr.robie.machineengine.test.LifecycleTest.assertHasError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registry aliases: a renamed entry keeps loading from saved data and definitions under its old key. */
class AliasTest {
    private static final Key OLD = Key.key("oldmod", "juice");
    private static final Key NEW = Key.key("newmod", "juice");
    private static final ResourceCategory LIQUID = ResourceCategory.builder(Key.key("pack", "liquid")).unit("mB").build();
    private static final ResourceType OLD_JUICE = ResourceType.of(OLD, LIQUID);
    private static final ResourceType NEW_JUICE = ResourceType.of(NEW, LIQUID);
    private static final WorldPosition TANK_AT = FakeWorld.at(0, 64, 0);

    /** A tank whose definition still names the old key. */
    private static final List<DefinitionFile> TANK = List.of(new DefinitionFile("definitions/tank.yml", map(
            "machines", map("pack:tank", map(
                    "storages", map("tank", map("capacity", 1000, "filter", map("type", OLD.asString()))),
                    "ports", map("in", map("storage", "tank", "direction", "input")),
                    "behavior", map("type", "machineengine:storage"))))));

    private static CoreMachineEngine before() {
        return TestEngine.builder().addon("oldmod", ctx -> {
            ctx.resourceCategories().register(LIQUID);
            ctx.resourceTypes().register(OLD_JUICE);
        }).definitions(TANK).start();
    }

    /** The resource moved to {@code newmod}, which keeps the old key working. */
    private static CoreMachineEngine after() {
        return TestEngine.builder().addon("newmod", ctx -> {
            ctx.resourceCategories().register(LIQUID);
            ctx.resourceTypes().register(NEW_JUICE);
            ctx.resourceTypes().alias(OLD, NEW);
        }).definitions(TANK).start();
    }

    @Test
    void lookupsFollowTheAlias() {
        CoreMachineEngine engine = after();
        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        var types = engine.registries().registry(Registries.RESOURCE_TYPE);
        assertSame(NEW_JUICE, types.get(OLD).orElseThrow());
        assertTrue(types.contains(OLD));
        assertFalse(types.keys().contains(OLD), "aliases are not entries");
        assertEquals(Map.of(OLD, NEW), types.aliases());
        assertEquals("newmod", types.owner(OLD).orElseThrow().name());
    }

    @Test
    void savedDataUnderTheOldKeyLoadsAndIsSavedUnderTheNewOne() {
        FakeWorld old = FakeWorld.singleRegion(before());
        old.place(old.engine().registries().registry(Registries.MACHINE_TYPE).getOrThrow(Key.key("pack", "tank")), TANK_AT, Direction.NORTH);
        ResourceHandler port = old.machine(TANK_AT).capability(Capabilities.RESOURCE_HANDLER, Direction.NORTH).orElseThrow();
        try (Transaction transaction = old.engine().transactions().openOuter()) {
            assertEquals(600, port.insert(OLD_JUICE.variant(), 600, transaction));
            transaction.commit();
        }
        DataMap saved = old.save(TANK_AT);
        assertTrue(saved.toString().contains(OLD.asString()), saved.toString());

        FakeWorld upgraded = FakeWorld.singleRegion(after());
        upgraded.load(Map.of(TANK_AT, saved));
        List<StorageView> tank = upgraded.inspect(TANK_AT).storages().get("tank");
        assertSame(NEW_JUICE, tank.getFirst().variant().type());
        assertEquals(600, tank.getFirst().amount());

        DataMap resaved = upgraded.save(TANK_AT);
        assertTrue(resaved.toString().contains(NEW.asString()), resaved.toString());
        assertFalse(resaved.toString().contains(OLD.asString()), resaved.toString());
    }

    @Test
    void definitionsNamingTheOldKeyAcceptTheNewType() {
        FakeWorld world = FakeWorld.singleRegion(after());
        world.place(world.engine().registries().registry(Registries.MACHINE_TYPE).getOrThrow(Key.key("pack", "tank")), TANK_AT, Direction.NORTH);
        ResourceHandler port = world.machine(TANK_AT).capability(Capabilities.RESOURCE_HANDLER, Direction.NORTH).orElseThrow();
        try (Transaction transaction = world.engine().transactions().openOuter()) {
            assertEquals(5, port.insert(NEW_JUICE.variant(), 5, transaction), "the filter {type: oldmod:juice} means newmod:juice");
        }
    }

    @Test
    void anAliasToAMissingEntryOrToAnotherAliasFailsItsAddon() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("newmod", ctx -> {
                    ctx.resourceCategories().register(LIQUID);
                    ctx.resourceTypes().register(NEW_JUICE);
                })
                .addon("dangling", ctx -> ctx.resourceTypes().alias(Key.key("dangling", "a"), Key.key("nowhere", "b")))
                .addon("chained", ctx -> {
                    ctx.resourceTypes().alias(Key.key("chained", "a"), Key.key("chained", "b"));
                    ctx.resourceTypes().alias(Key.key("chained", "b"), NEW);
                })
                .start();

        assertHasError(engine, DiagnosticCodes.INVALID_ALIAS, "dangling");
        assertHasError(engine, DiagnosticCodes.INVALID_ALIAS, "chained");
        assertEquals(Set.of("dangling", "chained"), names(engine.disabledAddons()));
        assertTrue(engine.registries().registry(Registries.RESOURCE_TYPE).aliases().isEmpty(), "the failed addons' aliases are gone");
    }

    @Test
    void anAliasInAReservedNamespaceFailsItsAddon() {
        CoreMachineEngine engine = TestEngine.builder().addon("newmod", ctx -> {
            ctx.resourceCategories().register(LIQUID);
            ctx.resourceTypes().register(NEW_JUICE);
            ctx.resourceTypes().alias(Key.key("machineengine", "juice"), NEW);
        }).start();
        assertHasError(engine, DiagnosticCodes.RESERVED_NAMESPACE, "newmod");
    }

    @Test
    void anAliasCannotAlsoBeAnEntry() {
        AtomicReference<RegistrationException> aliasOverEntry = new AtomicReference<>();
        AtomicReference<RegistrationException> entryOverAlias = new AtomicReference<>();
        TestEngine.builder().addon("newmod", ctx -> {
            ctx.resourceCategories().register(LIQUID);
            ctx.resourceTypes().register(NEW_JUICE);
            aliasOverEntry.set(assertThrows(RegistrationException.class, () -> ctx.resourceTypes().alias(NEW, OLD)));
            ctx.resourceTypes().alias(OLD, NEW);
            entryOverAlias.set(assertThrows(RegistrationException.class, () -> ctx.resourceTypes().register(OLD_JUICE)));
            assertThrows(RegistrationException.class, () -> ctx.resourceTypes().alias(OLD, NEW), "already an alias");
            assertThrows(IllegalArgumentException.class, () -> ctx.resourceTypes().alias(NEW, NEW));
        }).start();
        assertEquals(DiagnosticCodes.DUPLICATE_KEY, aliasOverEntry.get().diagnostic().code());
        assertEquals(DiagnosticCodes.DUPLICATE_KEY, entryOverAlias.get().diagnostic().code());
    }

    private static Set<String> names(Set<AddonId> addons) {
        return addons.stream().map(AddonId::name).collect(Collectors.toSet());
    }
}
