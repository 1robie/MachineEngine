package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.core.machine.LoadedMachine;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code type: machineengine_menu}: opens another menu for the same machine ({@code menu: <file name>});
 * without {@code menu}, the machine's own menu (a back button). zMenu's own inventory button would
 * lose which machine the menu shows.
 * <ul>
 *   <li>{@code group: items}: shown only for machines with ports in that group (side configuration tabs);</li>
 *   <li>{@code groups: [items, energy, ...]}: opens {@code <menu>_<group>} for the first group the
 *       machine has (the side configuration of whatever the machine has).</li>
 * </ul>
 */
final class OpenMenuButton extends Button {
    private final ZMenuMachineMenus menus;
    private final @Nullable String menu;
    private final @Nullable String group;
    private final List<String> groups;

    private OpenMenuButton(ZMenuMachineMenus menus, @Nullable String menu, @Nullable String group, List<String> groups) {
        this.menus = menus;
        this.menu = menu;
        this.group = group;
        this.groups = List.copyOf(groups);
    }

    @Override
    public boolean checkPermission(@NotNull Player player, @NotNull InventoryEngine inventory, @NotNull Placeholders placeholders) {
        if (!super.checkPermission(player, inventory, placeholders)) return false;
        if (this.group == null && this.groups.isEmpty()) return true;
        LoadedMachine machine = this.menus.machineOf(player);
        return machine != null && machine.instance() != null && target(machine.instance().type()) != null;
    }

    /** The menu to open for {@code type}, or {@code null} if it has none of the groups. */
    private @Nullable String target(MachineType type) {
        if (this.group != null && !SideButton.hasGroup(type, this.group)) return null;
        if (this.groups.isEmpty()) return this.menu;
        for (String candidate : this.groups) {
            if (SideButton.hasGroup(type, candidate)) return this.menu + "_" + candidate;
        }
        return null;
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public void onClick(@NotNull Player player, @NotNull InventoryClickEvent event, @NotNull InventoryEngine inventory, int slot,
                        @NotNull Placeholders placeholders) {
        super.onClick(player, event, inventory, slot, placeholders);
        LoadedMachine machine = this.menus.machineOf(player);
        if (machine == null || machine.instance() == null) return;
        String target = this.group == null && this.groups.isEmpty() ? this.menu : target(machine.instance().type());
        if (target == null && (this.group != null || !this.groups.isEmpty())) return;
        // Next tick: opening an inventory from inside its click event is not allowed.
        this.menus.scheduler().run(player, () -> this.menus.open(player, machine, target), null);
    }

    static final class Loader extends ButtonLoader {
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_menu");
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            return new OpenMenuButton(this.menus, configuration.getString(path + "menu"), configuration.getString(path + "group"),
                    configuration.getStringList(path + "groups"));
        }
    }
}
