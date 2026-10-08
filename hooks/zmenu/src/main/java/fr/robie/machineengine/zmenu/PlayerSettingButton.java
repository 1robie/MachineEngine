package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.button.DefaultButtonValue;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.loader.ButtonLoader;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerSetting;
import fr.robie.machineengine.core.player.CorePlayers;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code type: machineengine_player_setting} (D37): changes one of the player's own settings
 * ({@code setting: <namespace>:<id>}). A left click toggles a switch, goes to the next choice or adds one
 * step; a right click goes the other way. Hidden when the setting is not installed or the server locked it.
 * Show the value with {@code %zmenu_machineengine_player_<namespace>_<id>%}.
 */
final class PlayerSettingButton extends Button {
    private final Key setting;

    private PlayerSettingButton(Key setting) {
        this.setting = setting;
    }

    static @Nullable CorePlayers players() {
        return MachineEngine.get().players() instanceof CorePlayers players ? players : null;
    }

    private @Nullable PlayerSetting<?> setting(CorePlayers players) {
        PlayerSetting<?> found = players.setting(this.setting);
        return found == null || players.isLocked(found) ? null : found;
    }

    @Override
    public boolean checkPermission(@NotNull Player player, @NotNull InventoryEngine inventory, @NotNull Placeholders placeholders) {
        CorePlayers players = players();
        return super.checkPermission(player, inventory, placeholders) && players != null && setting(players) != null
                && players.find(player.getUniqueId()).isPresent();
    }

    @Override
    public boolean isClickable() {
        return true;
    }

    @Override
    public void onClick(@NotNull Player player, @NotNull InventoryClickEvent event, @NotNull InventoryEngine inventory, int slot,
                        @NotNull Placeholders placeholders) {
        super.onClick(player, event, inventory, slot, placeholders);
        CorePlayers players = players();
        PlayerSetting<?> found = players == null ? null : setting(players);
        MachinePlayer data = players == null ? null : players.find(player.getUniqueId()).orElse(null);
        if (found == null || data == null) return;
        step(data, found, !event.isRightClick());
        for (int shown : getSlots()) inventory.cancel(shown);
        inventory.buildButton(this, new Placeholders());
    }

    private static <T> void step(MachinePlayer data, PlayerSetting<T> setting, boolean forward) {
        data.set(setting, setting.format(setting.step(data.get(setting), forward)));
    }

    static final class Loader extends ButtonLoader {
        private final Plugin owner;

        Loader(Plugin plugin) {
            super(plugin, "machineengine_player_setting");
            this.owner = plugin;
        }

        @Override
        public @Nullable Button load(@NotNull YamlConfiguration configuration, @NotNull String path, @NotNull DefaultButtonValue defaultButtonValue) {
            String setting = configuration.getString(path + "setting");
            if (setting == null || !Key.parseable(setting)) {
                this.owner.getLogger().warning("The machineengine_player_setting button at " + path + " needs 'setting: <namespace>:<id>'");
                return null;
            }
            return new PlayerSettingButton(Key.key(setting));
        }
    }
}
