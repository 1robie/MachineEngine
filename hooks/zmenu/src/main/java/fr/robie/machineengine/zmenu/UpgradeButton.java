package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.machine.MachineAction;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.upgrade.InstalledUpgrade;
import fr.robie.machineengine.api.upgrade.UpgradeActions;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code type: machineengine_upgrades}: the machine's upgrade tab. Clicking with an upgrade on the cursor
 * installs as many as the machine takes (right-click: one), through the machine's {@code upgrade} action
 * (D40). Clicking with an empty hand opens the upgrades menu: {@code menu: <file name>}, else
 * {@code <the machine's menu>_upgrades}; when there is none, the last installed upgrade comes back as an item. Show the installed upgrades
 * with {@code %zmenu_machineengine_upgrades%}.
 *
 * <pre>{@code
 * upgrades:
 *   type: machineengine_upgrades
 *   slot: 8
 *   item: { material: NETHER_STAR, name: "&bUpgrades", lore: ["&7%zmenu_machineengine_upgrades%"] }
 * }</pre>
 */
final class UpgradeButton extends Button {
    private final ZMenuMachineMenus menus;
    private final @Nullable String menu;

    private UpgradeButton(ZMenuMachineMenus menus, @Nullable String menu) {
        this.menus = menus;
        this.menu = menu;
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public void onClick(@NotNull Player player, @NotNull InventoryClickEvent event, @NotNull InventoryEngine inventory, int slot,
                        @NotNull Placeholders placeholders) {
        super.onClick(player, event, inventory, slot, placeholders);
        MachineInstanceImpl machine = this.menus.ownedMachineOf(player);
        if (machine == null) return;
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir()) {
            MachineAction.Outcome outcome = machine.runAction(UpgradeActions.ACTION, new MachineAction.Click(player.getUniqueId(),
                    ActionButton.kind(event.getClick()), 0, PaperItems.toResource(cursor)));
            if (outcome.used() > 0) player.setItemOnCursor(ActionButton.remove(cursor, outcome.used()));
            ActionButton.finish(this.menus, player, machine, outcome);
            return;
        }
        String upgradesMenu = this.menu != null ? this.menu : this.menus.menuName(machine.type().key()) + "_upgrades";
        if (this.menus.open(player, machine, upgradesMenu)) return;
        List<InstalledUpgrade> upgrades = machine.upgrades();
        if (upgrades.isEmpty()) return;
        ActionButton.finish(this.menus, player, machine, UpgradeActions.take(machine, upgrades.getLast().type(), 1));
    }

    static final class Loader extends ButtonLoader {
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_upgrades");
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            return new UpgradeButton(this.menus, configuration.getString(path + "menu"));
        }
    }
}
