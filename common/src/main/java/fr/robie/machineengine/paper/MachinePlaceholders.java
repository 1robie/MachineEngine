package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.paper.ResourceColors;
import fr.robie.machineengine.api.paper.ResourceNames;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.upgrade.InstalledUpgrade;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Machine values for menus (§23.3), independent of the menu plugin. Keys (after the menu plugin's
 * prefix, e.g. {@code %zmenu_machineengine_<key>%}):
 *
 * <pre>
 * type  type_namespace  type_value  placeholder  state  last_wake  error  facing  x  y  z  world
 * storage_&lt;id&gt;_amount  _capacity  _percent  _bar  _resource  _name  _free  _color  _cell_&lt;k&gt;
 * storage_&lt;id&gt;_slot_&lt;n&gt;_amount  _capacity  _resource  _name  _color  _cell_&lt;k&gt;   (n starts at 1)
 * process_active  process_recipe  process_status  process_progress  process_duration
 * process_remaining  process_remaining_seconds  process_percent  process_bar  process_cell_&lt;k&gt;
 * upgrades  upgrade_&lt;namespace&gt;_&lt;value&gt;  escrow
 * </pre>
 *
 * Bars are 10 characters of {@code █} and {@code ░}; percents are whole numbers. For textured menus,
 * {@code _cell_<k>} is the level of cell k (0 to 2) of a 3-slot fill bar ({@link BarCells}), and
 * {@code _color} the resource's color as {@code #RRGGBB} ({@link ResourceColors}).
 */
public final class MachinePlaceholders {
    private static final int BAR_LENGTH = 10;

    private MachinePlaceholders() {
    }

    /** @return the value, or {@code null} if {@code key} is not a machine placeholder */
    public static @Nullable String resolve(@NotNull MachineInspection machine, @NotNull String key) {
        String k = key.toLowerCase(Locale.ROOT);
        switch (k) {
            case "type": return machine.type().asString();
            case "type_namespace": return machine.type().namespace();
            case "type_value": return machine.type().value();
            case "placeholder": return Boolean.toString(machine.placeholder());
            case "state": return machine.state().name().toLowerCase(Locale.ROOT);
            case "last_wake": return machine.lastWakeReason() == null ? "" : machine.lastWakeReason().key().asString();
            case "error": return machine.lastError() == null ? "" : machine.lastError().message();
            case "facing": return machine.facing().name().toLowerCase(Locale.ROOT);
            case "x": return Integer.toString(machine.position().position().x());
            case "y": return Integer.toString(machine.position().position().y());
            case "z": return Integer.toString(machine.position().position().z());
            case "world": return machine.position().world().asString();
            case "upgrades": return Long.toString(machine.upgrades().stream().mapToLong(InstalledUpgrade::count).sum());
            case "escrow": return Long.toString(machine.escrow().stream().mapToLong(ResourceStack::amount).sum());
            default: break;
        }
        if (k.startsWith("process_")) return process(machine.process(), k.substring("process_".length()));
        if (k.startsWith("storage_")) return storage(machine, k.substring("storage_".length()));
        if (k.startsWith("upgrade_")) {
            String name = k.substring("upgrade_".length());
            return Integer.toString(machine.upgrades().stream()
                    .filter(upgrade -> (upgrade.type().key().namespace() + "_" + upgrade.type().key().value()).equals(name))
                    .mapToInt(InstalledUpgrade::count).sum());
        }
        return null;
    }

    private static @Nullable String process(@Nullable ProcessView process, String key) {
        if (key.equals("active")) return Boolean.toString(process != null);
        if (process == null) {
            return switch (key) {
                case "recipe", "status" -> "";
                case "progress", "duration", "remaining", "remaining_seconds", "percent" -> "0";
                case "bar" -> bar(0, 1);
                default -> key.startsWith("cell_") ? cell(0, 1, key) : null;
            };
        }
        return switch (key) {
            case "recipe" -> process.recipe().asString();
            case "status" -> process.status().name().toLowerCase(Locale.ROOT);
            case "progress" -> Long.toString(process.progress());
            case "duration" -> Long.toString(process.duration());
            case "remaining" -> Long.toString(process.remaining());
            case "remaining_seconds" -> Long.toString((process.remaining() + 19) / 20);
            case "percent" -> Long.toString(percent(process.progress(), process.duration()));
            case "bar" -> bar(process.progress(), process.duration());
            default -> key.startsWith("cell_") ? cell(process.progress(), process.duration(), key) : null;
        };
    }

    /** {@code <id>_<field>} or {@code <id>_slot_<n>_<field>}; storage ids may contain underscores. */
    private static @Nullable String storage(MachineInspection machine, String rest) {
        // Longest id first, so "fuel_tank_amount" picks storage "fuel_tank" over storage "fuel".
        List<Map.Entry<String, List<StorageView>>> entries = machine.storages().entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length())).toList();
        for (var entry : entries) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            if (!rest.startsWith(id + "_")) continue;
            String field = rest.substring(id.length() + 1);
            List<StorageView> slots = entry.getValue();
            if (field.startsWith("slot_")) {
                String[] parts = field.substring("slot_".length()).split("_", 2);
                if (parts.length != 2) return null;
                int index;
                try {
                    index = Integer.parseInt(parts[0]) - 1;
                } catch (NumberFormatException e) {
                    return null;
                }
                if (index < 0 || index >= slots.size()) return null;
                return slotField(slots.get(index), parts[1]);
            }
            long amount = slots.stream().mapToLong(StorageView::amount).sum();
            long capacity = slots.stream().mapToLong(StorageView::capacity).sum();
            return switch (field) {
                case "amount" -> Long.toString(amount);
                case "capacity" -> Long.toString(capacity);
                case "free" -> Long.toString(capacity - amount);
                case "percent" -> Long.toString(percent(amount, capacity));
                case "bar" -> bar(amount, capacity);
                case "resource" -> slots.stream().filter(view -> !view.isEmpty()).findFirst()
                        .map(view -> describe(view)).orElse("");
                case "name" -> ResourceNames.of(slots.stream().filter(view -> !view.isEmpty()).findFirst()
                        .map(StorageView::variant).orElse(null));
                case "color" -> ResourceColors.hex(ResourceColors.of(slots.stream().filter(view -> !view.isEmpty()).findFirst()
                        .map(StorageView::variant).orElse(null)));
                default -> field.startsWith("cell_") ? cell(amount, capacity, field) : null;
            };
        }
        return null;
    }

    private static @Nullable String slotField(StorageView slot, String field) {
        return switch (field) {
            case "amount" -> Long.toString(slot.amount());
            case "capacity" -> Long.toString(slot.capacity());
            case "resource" -> slot.isEmpty() ? "" : describe(slot);
            case "name" -> ResourceNames.of(slot.isEmpty() ? null : slot.variant());
            case "color" -> ResourceColors.hex(ResourceColors.of(slot.isEmpty() ? null : slot.variant()));
            default -> field.startsWith("cell_") ? cell(slot.amount(), slot.capacity(), field) : null;
        };
    }

    /** {@code cell_<k>}: the level of cell k of a fill bar, or {@code null} for a bad cell. */
    private static @Nullable String cell(long value, long total, String key) {
        int cell;
        try {
            cell = Integer.parseInt(key.substring("cell_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
        int level = BarCells.level(value, total, cell);
        return level < 0 ? null : Integer.toString(level);
    }

    private static String describe(StorageView view) {
        return view.variant() == null ? "" : view.variant().type().key().asString();
    }

    static long percent(long value, long total) {
        return total <= 0 ? 0 : Math.min(100, value * 100 / total);
    }

    static String bar(long value, long total) {
        int filled = (int) (total <= 0 ? 0 : Math.min(BAR_LENGTH, value * BAR_LENGTH / total));
        return "█".repeat(filled) + "░".repeat(BAR_LENGTH - filled);
    }
}
