package fr.robie.machineengine.api.player;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.event.EventType;
import org.jetbrains.annotations.NotNull;

/** Player events (D37), posted in the {@code POST} phase. */
public final class PlayerEvents {
    /** A player (or an admin) changed a {@link PlayerSetting}; posted on the player's thread. */
    public static final EventType<SettingChanged> SETTING_CHANGED = EventType.of(MachineEngineKeys.key("player_setting_changed"), SettingChanged.class);

    private PlayerEvents() {
    }

    public record SettingChanged(@NotNull MachinePlayer player, @NotNull PlayerSetting<?> setting, @NotNull Object oldValue,
                                 @NotNull Object newValue) {
    }
}
