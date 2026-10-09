package fr.robie.machineengine.api.upgrade;

import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.machine.MachineAction;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.machine.MachineList;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The upgrade action and list every machine type with upgrade slots gets (D40), unless it declares its own
 * with the same ids.
 * <ul>
 *   <li>action {@value #ACTION}: with an upgrade item held, installs as many as fit (right-click: one);
 *       with nothing held, takes upgrades of row {@code index} of the list out as items (one; shift: all);</li>
 *   <li>list {@value #LIST}: every upgrade the machine accepts, then any installed one it no longer accepts,
 *       in registry order. Fields: {@code id}, {@code namespace}, {@code value}, {@code count}, {@code max},
 *       {@code installed} ({@code true}/{@code false}), {@code item} (the id of an item installing it, or empty).</li>
 * </ul>
 */
public final class UpgradeActions {
    public static final String ACTION = "upgrade";
    public static final String LIST = "upgrades";

    private UpgradeActions() {
    }

    public static final MachineAction UPGRADE = UpgradeActions::run;

    public static final MachineList UPGRADES = context -> {
        List<Map<String, String>> rows = new ArrayList<>();
        for (UpgradeType upgrade : rows(context)) {
            int count = installed(context.machine(), upgrade);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", upgrade.key().asString());
            row.put("namespace", upgrade.key().namespace());
            row.put("value", upgrade.key().value());
            row.put("count", Integer.toString(count));
            row.put("max", Integer.toString(upgrade.maxStack()));
            row.put("installed", Boolean.toString(count > 0));
            ResourceVariant item = UpgradeItems.itemOf(upgrade);
            ItemData data = item == null ? null : item.components().get(Items.DATA);
            row.put("item", data == null ? "" : data.item().asString());
            rows.add(row);
        }
        return rows;
    };

    private static MachineAction.Outcome run(MachineContext context, MachineAction.Click click) {
        if (click.held() != null) return install(context, click.held(), click.kind() == MachineAction.ClickKind.RIGHT);
        if (click.kind() == MachineAction.ClickKind.INSERT) return MachineAction.Outcome.nothing();
        List<UpgradeType> rows = rows(context);
        if (click.index() >= rows.size()) return MachineAction.Outcome.nothing();
        return take(context.machine(), rows.get(click.index()), click.kind() == MachineAction.ClickKind.SHIFT ? Integer.MAX_VALUE : 1);
    }

    /** Installs as many of the held upgrade as fit ({@code one}: a single one). */
    public static @NotNull MachineAction.Outcome install(@NotNull MachineContext context, @NotNull ResourceStack held, boolean one) {
        UpgradeType upgrade = UpgradeItems.upgradeOf(held.variant(), context.registries().registry(Registries.UPGRADE_TYPE));
        if (upgrade == null) return MachineAction.Outcome.refused(Component.text("That is not an upgrade", NamedTextColor.RED));
        MachineInstance machine = context.machine();
        Result<Integer> room = machine.upgradeRoom(upgrade);
        if (room.isErr()) return MachineAction.Outcome.refused(Component.text(room.error().message(), NamedTextColor.RED));
        int count = (int) Math.min(room.value(), one ? 1 : held.amount());
        if (count <= 0) return MachineAction.Outcome.nothing();
        Result<InstalledUpgrade> installed = machine.installUpgrade(upgrade, count);
        if (installed.isErr()) return MachineAction.Outcome.refused(Component.text(installed.error().message(), NamedTextColor.RED));
        return MachineAction.Outcome.used(count);
    }

    /** Takes up to {@code count} of {@code upgrade} out, as items; nothing is removed if it has no item. */
    public static @NotNull MachineAction.Outcome take(@NotNull MachineInstance machine, @NotNull UpgradeType upgrade, int count) {
        if (installed(machine, upgrade) == 0) return MachineAction.Outcome.nothing();
        ResourceVariant item = UpgradeItems.itemOf(upgrade);
        if (item == null) {
            return MachineAction.Outcome.refused(Component.text("No item installs " + upgrade.key().asString()
                    + "; it stays in the machine", NamedTextColor.RED));
        }
        int removed = machine.removeUpgrade(upgrade, count);
        return removed <= 0 ? MachineAction.Outcome.nothing() : MachineAction.Outcome.give(new ResourceStack(item, removed));
    }

    /** The list's rows: accepted upgrades, then installed ones no longer accepted. */
    static List<UpgradeType> rows(MachineContext context) {
        Registry<UpgradeType> registry = context.registries().registry(Registries.UPGRADE_TYPE);
        Set<UpgradeType> rows = new LinkedHashSet<>();
        for (UpgradeType upgrade : registry.values()) {
            if (context.type().acceptsUpgrade(upgrade)) rows.add(upgrade);
        }
        context.machine().upgrades().forEach(installed -> rows.add(installed.type()));
        return List.copyOf(rows);
    }

    private static int installed(MachineInstance machine, UpgradeType upgrade) {
        return machine.upgrades().stream().filter(u -> u.type().equals(upgrade)).mapToInt(InstalledUpgrade::count).findFirst().orElse(0);
    }
}
