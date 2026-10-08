package fr.robie.machineengine.paper.player;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.api.player.PlayerSetting;
import fr.robie.machineengine.core.player.CorePlayers;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Player values as text (D37): placeholders, admin commands. */
public final class PlayerValues {
    private PlayerValues() {
    }

    /**
     * The placeholder {@code player_<namespace>_<id>} (the part after {@code zmenu_machineengine_}): a
     * setting's value (owner default and lock applied) or a key's {@link PlayerDataKey#display}.
     *
     * @return {@code null} if {@code name} names no registered key
     */
    public static @Nullable String placeholder(@NotNull CorePlayers players, @NotNull MachinePlayer player, @NotNull String name) {
        for (PlayerDataKey<?> key : players.keys()) {
            if (!(key.key().namespace() + "_" + key.key().value().replace('/', '_')).equals(name)) continue;
            PlayerSetting<?> setting = players.setting(key.key());
            return setting != null ? settingText(player, setting) : text(player, key);
        }
        return null;
    }

    public static <T> @NotNull String settingText(@NotNull MachinePlayer player, @NotNull PlayerSetting<T> setting) {
        return setting.format(player.get(setting));
    }

    public static <T> @NotNull String text(@NotNull MachinePlayer player, @NotNull PlayerDataKey<T> key) {
        return key.format(player.get(key));
    }

    /** The registered key named {@code text} ({@code namespace:id}), settings included. */
    public static @Nullable PlayerDataKey<?> key(@NotNull CorePlayers players, @NotNull String text) {
        Result<Key> key = Codecs.parseKey(text.trim().toLowerCase(Locale.ROOT));
        return key.isOk() ? players.key(key.value()) : null;
    }

    /**
     * Sets {@code key} from what an admin typed: a setting's own syntax (a lock does not stop an admin), else
     * text read as the type of the key's default (true/false, a whole number, a number, an enum constant, text).
     *
     * @return an error message, or {@code null} once set
     */
    public static @Nullable String set(@NotNull CorePlayers players, @NotNull MachinePlayer player, @NotNull PlayerDataKey<?> key,
                                       @NotNull String text) {
        PlayerSetting<?> setting = players.setting(key.key());
        if (setting != null) return setSetting(player, setting, text);
        return setRaw(player, key, text);
    }

    private static <T> @Nullable String setSetting(MachinePlayer player, PlayerSetting<T> setting, String text) {
        T value = setting.parse(text);
        if (value == null) return "'" + text + "' is not " + setting.expected();
        player.set(setting.data(), value);
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> @Nullable String setRaw(MachinePlayer player, PlayerDataKey<T> key, String text) {
        Object current = key.defaultValue();
        Object value;
        try {
            if (current instanceof Boolean) {
                value = switch (text.trim().toLowerCase(Locale.ROOT)) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw new IllegalArgumentException();
                };
            } else if (current instanceof Long) {
                value = Long.parseLong(text.trim());
            } else if (current instanceof Integer) {
                value = Integer.parseInt(text.trim());
            } else if (current instanceof Double) {
                value = Double.parseDouble(text.trim());
            } else if (current instanceof String) {
                value = text;
            } else if (current instanceof Enum<?> constant) {
                value = Enum.valueOf((Class) constant.getDeclaringClass(), text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } else {
                return key.key().asString() + " holds " + current.getClass().getSimpleName() + ", which cannot be typed in a command";
            }
        } catch (IllegalArgumentException e) {
            return "'" + text + "' is not a " + current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
        player.set(key, (T) value);
        return null;
    }
}
