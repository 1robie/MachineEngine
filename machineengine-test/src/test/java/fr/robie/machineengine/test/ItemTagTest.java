package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.core.definition.DefinitionCompiler;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static fr.robie.machineengine.test.DefinitionTest.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Item tags (E10): addon contributions, includes, the platform's tags, and reloads. */
class ItemTagTest {
    private static final Key OSMIUM_ORE = Key.key("pack", "osmium_ore");
    private static final Key IRON_ORE = Key.key("minecraft", "iron_ore");
    private static final Key STONE = Key.key("minecraft", "stone");

    private static ResourceVariant item(Key id) {
        return Items.variant(new ItemData(id, new byte[] {1}));
    }

    private static DataMap tags(String... osmiumOres) {
        return map("item-tags", map(
                "c:ores/osmium", List.of(osmiumOres),
                "c:ores", List.of("#c:ores/osmium", "#c:ores/iron", "#c:ores"), // includes itself: must not loop
                "c:ores/iron", List.of("#minecraft:iron_ores")));
    }

    @Test
    void tagsCombineContributionsIncludesAndThePlatform() {
        CoreMachineEngine engine = TestEngine.builder()
                // The platform knows vanilla tags.
                .itemTags((tag, item) -> tag.equals(Key.key("minecraft", "iron_ores")) && item.equals(IRON_ORE))
                .definitions(List.of(new DefinitionFile("definitions/tags.yml", tags("pack:osmium_ore"))))
                .start();
        ResourcePredicate ores = Items.inTag(Key.key("c", "ores"));
        assertTrue(ores.test(item(OSMIUM_ORE), engine.tags()), "contributed, through an include");
        assertTrue(ores.test(item(IRON_ORE), engine.tags()), "vanilla, through two includes");
        assertFalse(ores.test(item(STONE), engine.tags()));
        assertFalse(ores.test(MachineFixtures.B.variant(), engine.tags()), "not an item");
    }

    @Test
    void reloadedTagsReachExistingMachines() {
        List<DefinitionFile> files = new ArrayList<>(List.of(new DefinitionFile("definitions/tags.yml", tags("pack:osmium_ore")),
                new DefinitionFile("definitions/bin.yml", map("machines", map("pack:ore_bin", map(
                        "storages", map("ores", map("capacity", 64, "filter", map("item-tag", "c:ores/osmium"))),
                        "behavior", map("type", "machineengine:storage")))))));
        CoreMachineEngine engine = TestEngine.builder().definitions(files).start();
        FakeWorld world = FakeWorld.singleRegion(engine);
        var bin = world.place(engine.registries().registry(Registries.MACHINE_TYPE).getOrThrow(Key.key("pack", "ore_bin")),
                FakeWorld.at(0, 64, 0), fr.robie.machineengine.api.world.Direction.NORTH);
        Key deepslate = Key.key("pack", "deepslate_osmium_ore");
        assertEquals(0, insert(world, bin, item(deepslate)), "not in the tag yet");

        files.set(0, new DefinitionFile("definitions/tags.yml", tags("pack:osmium_ore", "pack:deepslate_osmium_ore")));
        assertFalse(engine.reload(Registries.ITEM_TAG).hasErrors());
        assertEquals(1, insert(world, bin, item(deepslate)), "the reload reaches the existing machine");
    }

    @Test
    void resourceTagsIncludeTagsOfTheSameFilesAndReportUnknownMembers() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon(MachineFixtures.alpha())
                .definitions(List.of(new DefinitionFile("definitions/tags.yml", map("resource-tags", map(
                        "pack:matter", List.of("alpha:b", "pack:ghost"),
                        "pack:all", List.of("#pack:matter"))))))
                .start();
        assertTrue(engine.tags().contains(Key.key("pack", "all"), MachineFixtures.B));
        assertTrue(engine.startupReport().warnings().stream().anyMatch(d -> d.code().equals(DiagnosticCodes.DEFINITION_SKIPPED)
                && d.location().equals("definitions/tags.yml > resource-tags.pack:matter") && d.message().contains("pack:ghost")));
    }

    @Test
    void recipesWaitUntilThePlatformsItemsAreReady() {
        AtomicBoolean ready = new AtomicBoolean(false);
        DefinitionCompiler.ItemResolver items = new DefinitionCompiler.ItemResolver() {
            @Override
            public Result<ResourceVariant> item(Key id) {
                return Result.ok(ItemTagTest.item(id));
            }

            @Override
            public boolean ready() {
                return ready.get();
            }
        };
        DataMap recipes = map(
                "recipe-types", List.of("pack:crushing"),
                "recipes", map("pack:crush", map("type", "pack:crushing", "duration", 5,
                        "inputs", List.of(map("item", "minecraft:iron_ore", "amount", 1)),
                        "outputs", List.of(map("item", "pack:iron_dust", "amount", 2)))));
        CoreMachineEngine engine = TestEngine.builder()
                .definitions(() -> List.of(new DefinitionFile("definitions/recipes.yml", recipes)), items)
                .start();
        assertTrue(engine.startupReport().warnings().isEmpty(), "no noise while the items are not loaded: " + engine.startupReport().format());
        assertFalse(engine.registries().registry(Registries.RECIPE).contains(Key.key("pack", "crush")));

        ready.set(true);
        assertFalse(engine.reload(Registries.RECIPE).hasErrors());
        assertTrue(engine.registries().registry(Registries.RECIPE).contains(Key.key("pack", "crush")));
    }

    private static long insert(FakeWorld world, fr.robie.machineengine.core.machine.MachineInstanceImpl machine, ResourceVariant variant) {
        try (var transaction = world.engine().transactions().openOuter()) {
            long inserted = machine.context().storage("ores").insert(variant, 1, transaction);
            transaction.commit();
            return inserted;
        }
    }
}
