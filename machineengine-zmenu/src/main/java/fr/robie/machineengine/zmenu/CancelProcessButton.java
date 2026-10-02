package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code type: machineengine_cancel_process}: cancels the open machine's process; its inputs go back
 * to the input storages (D15). Hidden when no process runs, unless {@code always-show: true}.
 */
final class CancelProcessButton extends Button {
    private final ZMenuMachineMenus menus;
    private final boolean alwaysShow;

    private CancelProcessButton(ZMenuMachineMenus menus, boolean alwaysShow) {
        this.menus = menus;
        this.alwaysShow = alwaysShow;
    }

    @Override
    public boolean checkPermission(@NotNull Player player, @NotNull InventoryEngine inventory, @NotNull Placeholders placeholders) {
        MachineInstanceImpl machine = machine(player);
        return super.checkPermission(player, inventory, placeholders)
                && (this.alwaysShow || (machine != null && machine.context().process().current() != null));
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public void onClick(@NotNull Player player, @NotNull InventoryClickEvent event, @NotNull InventoryEngine inventory, int slot,
                        @NotNull Placeholders placeholders) {
        super.onClick(player, event, inventory, slot, placeholders);
        MachineInstanceImpl machine = machine(player);
        if (machine != null) machine.context().process().cancel();
        this.menus.refresh(player);
    }

    private @Nullable MachineInstanceImpl machine(Player player) {
        LoadedMachine loaded = this.menus.machineOf(player);
        return loaded == null ? null : loaded.instance();
    }

    static final class Loader extends ButtonLoader {
        private final ZMenuMachineMenus menus;

        Loader(Plugin plugin, ZMenuMachineMenus menus) {
            super(plugin, "machineengine_cancel_process");
            this.menus = menus;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            return new CancelProcessButton(this.menus, configuration.getBoolean(path + "always-show", false));
        }
    }
}
