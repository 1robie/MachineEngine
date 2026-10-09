package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.paper.ResourceColors;
import fr.robie.machineengine.api.process.ProcessStatus;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MachinePlaceholdersTest {
    private static final ResourceType SPARK = ResourceType.of(Key.key("example", "spark"),
            ResourceCategory.builder(Key.key("example", "charge")).build());

    private static final MachineInspection MACHINE = new MachineInspection(Key.key("example", "consumer"),
            WorldPosition.of(Key.key("minecraft", "overworld"), 4, 100, -4), Direction.NORTH, false, SchedulerState.SCHEDULED,
            null, null,
            Map.of("input", List.of(new StorageView(SPARK.variant(), 15, 50)),
                    "fuel_tank", List.of(new StorageView(null, 0, 100), new StorageView(SPARK.variant(), 25, 100))),
            new ProcessView(Key.key("example", "burn_sparks"), ProcessStatus.RUNNING, 3, 10,
                    List.of(SPARK.stack(5)), List.of(), List.of()),
            List.of(), List.of(), Map.of(), List.of());

    @Test
    void resolvesMachineValues() {
        assertEquals("example:consumer", MachinePlaceholders.resolve(MACHINE, "type"));
        assertEquals("scheduled", MachinePlaceholders.resolve(MACHINE, "state"));
        assertEquals("-4", MachinePlaceholders.resolve(MACHINE, "z"));
        assertEquals("15", MachinePlaceholders.resolve(MACHINE, "storage_input_amount"));
        assertEquals("30", MachinePlaceholders.resolve(MACHINE, "storage_input_percent"));
        assertEquals("███░░░░░░░", MachinePlaceholders.resolve(MACHINE, "storage_input_bar"));
        assertEquals("example:spark", MachinePlaceholders.resolve(MACHINE, "storage_input_resource"));
        assertEquals("175", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_free"), "ids may contain underscores");
        assertEquals("25", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_slot_2_amount"));
        assertEquals("", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_slot_1_resource"));
        assertEquals("running", MachinePlaceholders.resolve(MACHINE, "process_status"));
        assertEquals("30", MachinePlaceholders.resolve(MACHINE, "process_percent"));
        assertEquals("1", MachinePlaceholders.resolve(MACHINE, "process_remaining_seconds"));
        assertEquals("0", MachinePlaceholders.resolve(MACHINE, "upgrade_example_speed"));
    }

    @Test
    void resolvesTexturedBarValues() {
        // input: 15 / 50 -> 15 of 52 px, all in the bottom cell.
        assertEquals("15", MachinePlaceholders.resolve(MACHINE, "storage_input_cell_0"));
        assertEquals("0", MachinePlaceholders.resolve(MACHINE, "storage_input_cell_1"));
        // fuel_tank: 25 / 200 -> 6 px; its second slot alone: 25 / 100 -> 13 px.
        assertEquals("6", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_cell_0"));
        assertEquals("13", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_slot_2_cell_0"));
        assertEquals("0", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_slot_1_cell_0"));
        // process: 3 / 10 -> 15 px.
        assertEquals("15", MachinePlaceholders.resolve(MACHINE, "process_cell_0"));
        assertEquals("#FFFFFF", MachinePlaceholders.resolve(MACHINE, "storage_input_color"), "no colorer: white");
        assertEquals("#FFFFFF", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_slot_1_color"));
    }

    @Test
    void percentsBecomeOneSlotLevels() {
        assertEquals("8", MachinePlaceholders.percentCell("50"));
        assertEquals("16", MachinePlaceholders.percentCell(" 100 "));
        assertNull(MachinePlaceholders.percentCell("idle"));
    }

    @Test
    void colorsComeFromColorers() {
        Function<ResourceVariant, Integer> colorer = variant -> variant.type() == SPARK ? 0xB30505 : null;
        ResourceColors.register(colorer);
        try {
            assertEquals("#B30505", MachinePlaceholders.resolve(MACHINE, "storage_input_color"));
            assertEquals("#B30505", MachinePlaceholders.resolve(MACHINE, "storage_fuel_tank_color"), "the first slot holding something");
        } finally {
            ResourceColors.unregister(colorer);
        }
    }

    @Test
    void unknownKeysAreLeftAlone() {
        assertNull(MachinePlaceholders.resolve(MACHINE, "nope"));
        assertNull(MachinePlaceholders.resolve(MACHINE, "storage_ghost_amount"));
        assertNull(MachinePlaceholders.resolve(MACHINE, "storage_input_slot_9_amount"));
        assertNull(MachinePlaceholders.resolve(MACHINE, "storage_input_cell_3"));
        assertNull(MachinePlaceholders.resolve(MACHINE, "process_cell_x"));
    }

    @Test
    void resolvesListRows() {
        Map<String, List<Map<String, String>>> lists = Map.of(
                "mods", List.of(Map.of("id", "jetpack", "level", "2"), Map.of("id", "magnet", "level", "1")),
                "mods_extra", List.of(Map.of("id", "extra")));
        Function<String, List<Map<String, String>>> rows = lists::get;
        Set<String> ids = lists.keySet();
        assertEquals("2", MachinePlaceholders.list(ids, rows, "list_mods_size"));
        assertEquals("jetpack", MachinePlaceholders.list(ids, rows, "list_mods_0_id"));
        assertEquals("1", MachinePlaceholders.list(ids, rows, "list_mods_1_level"));
        assertEquals("", MachinePlaceholders.list(ids, rows, "list_mods_2_id"), "past the end");
        assertEquals("", MachinePlaceholders.list(ids, rows, "list_mods_0_missing"), "a field the row lacks");
        assertEquals("extra", MachinePlaceholders.list(ids, rows, "list_mods_extra_0_id"), "the longest id wins");
        assertEquals("1", MachinePlaceholders.list(ids, rows, "list_mods_extra_size"));
        assertNull(MachinePlaceholders.list(ids, rows, "list_other_0_id"));
        assertNull(MachinePlaceholders.list(ids, rows, "list_mods_x_id"));
        assertNull(MachinePlaceholders.list(ids, rows, "storage_input_amount"));
    }
}
