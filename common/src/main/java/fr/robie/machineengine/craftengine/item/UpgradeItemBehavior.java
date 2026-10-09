package fr.robie.machineengine.craftengine.item;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.item.MachineTool;
import fr.robie.machineengine.api.item.UpgradeItem;
import fr.robie.machineengine.api.machine.MachineAction;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.paper.PaperItemBehavior;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.upgrade.UpgradeActions;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.paper.Messages;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The CraftEngine item behavior {@code machineengine:upgrade} (D40): the item is a machine upgrade.
 * Install it from the machine's menu, or right-click the machine with it (one; sneak: as many as fit).
 *
 * <pre>{@code
 * behaviors:
 *   - type: machineengine:upgrade
 *     upgrade: mekanized:upgrade_speed   # the upgrade type; the item's own id by default
 * }</pre>
 */
public final class UpgradeItemBehavior extends PaperItemBehavior implements UpgradeItem, MachineTool {
    public static final ItemBehaviorFactory<UpgradeItemBehavior> FACTORY = (pack, path, id, section) ->
            new UpgradeItemBehavior(Key.key(section.getString("upgrade", id.asString())));

    private final Key upgrade;

    UpgradeItemBehavior(Key upgrade) {
        this.upgrade = upgrade;
    }

    @Override
    public @NotNull Key upgrade() {
        return this.upgrade;
    }

    /** On a machine with upgrade slots: install; anywhere else, nothing (the machine's menu opens). */
    @Override
    protected boolean use(@NotNull Use use) {
        Block block = use.block();
        if (block == null) return false;
        MachineInstance machine = MachineEngine.get()
                .machineAt(WorldPosition.of(block.getWorld().getKey(), block.getX(), block.getY(), block.getZ())).orElse(null);
        if (machine == null || machine.type().action(UpgradeActions.ACTION).isEmpty()) return false;
        Player player = use.player();
        ItemStack item = use.item();
        MachineAction.Outcome outcome = machine.runAction(UpgradeActions.ACTION, new MachineAction.Click(player.getUniqueId(),
                use.sneaking() ? MachineAction.ClickKind.LEFT : MachineAction.ClickKind.RIGHT, 0, PaperItems.toResource(item)));
        if (outcome.used() > 0) {
            player.sendActionBar(Messages.message("machineengine.upgrade.installed", "§aInstalled %s × %s",
                    Component.text(outcome.used()), Component.text(this.upgrade.value().replace('_', ' '))));
            if (player.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - (int) outcome.used());
        } else if (outcome.message() != null) {
            player.sendActionBar(outcome.message());
        }
        return true;
    }
}
