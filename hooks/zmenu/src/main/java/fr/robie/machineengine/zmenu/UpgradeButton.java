package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.upgrade.InstalledUpgrade;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.api.paper.PaperItems;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * {@code type: machineengine_upgrades}: the machine's upgrade slots. An item whose id is a registered
 * upgrade type's key (e.g. {@code mekanized:upgrade_speed}) is that upgrade: clicking with it installs
 * as many as the machine takes; clicking with an empty hand takes one installed upgrade back as its
 * item. Show the installed upgrades with {@code %zmenu_machineengine_upgrades%}.
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

    private UpgradeButton(ZMenuMachineMenus menus) {
        this.menus = menus;
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
            UpgradeType upgrade = upgrade(machine, PaperItems.idOf(cursor));
            if (upgrade == null) return;
            int installed = machine.upgrades().stream().filter(u -> u.type().equals(upgrade)).mapToInt(InstalledUpgrade::count).findFirst().orElse(0);
            int count = Math.min(cursor.getAmount(), upgrade.maxStack() - installed);
            if (count <= 0 || machine.installUpgrade(upgrade, count).isErr()) return;
            player.setItemOnCursor(cursor.getAmount() == count ? null : cursor.asQuantity(cursor.getAmount() - count));
        } else {
            List<InstalledUpgrade> upgrades = machine.upgrades();
            if (upgrades.isEmpty()) return;
            UpgradeType upgrade = upgrades.getLast().type();
            ItemStack item = PaperItems.itemOf(upgrade.key());
            if (item == null || machine.removeUpgrade(upgrade, 1) != 1) return;
            Map<Integer, ItemStack> left = player.getInventory().addItem(item);
            left.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
        this.menus.changed(player, machine.position());
    }

    private static @Nullable UpgradeType upgrade(MachineInstanceImpl machine, Key itemId) {
        return machine.services().registries().registry(Registries.UPGRADE_TYPE).get(itemId).orElse(null);
    }

    static final class Loader extends ButtonLoader {
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_upgrades");
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            return new UpgradeButton(this.menus);
        }
    }
}
