package fr.robie.machineengine.paper.player;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerSetting;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.player.CorePlayers;
import fr.robie.machineengine.paper.MachineMenuService;
import fr.robie.machineengine.paper.Messages;
import fr.robie.paperdispatch.command.BaseCommand;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /machinesettings [<setting> [<value>|reset]]} (D37): players see and change their own settings.
 * Without arguments it opens the zMenu menu {@code player_settings} when zMenu is installed, else lists
 * them. A setting is named by its key ({@code mekanized:radiation_hud}) or, when no other addon uses the
 * same name, by its name alone ({@code radiation_hud}). Locked settings are not shown.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PlayerSettingsCommand extends BaseCommand<Plugin> {
    public static final String PERMISSION = "machineengine.settings";
    private final Supplier<CoreMachineEngine> engine;

    public PlayerSettingsCommand(@NotNull Plugin plugin, @NotNull Supplier<CoreMachineEngine> engine) {
        super(plugin, "machinesettings", "msettings");
        this.engine = engine;
        setDescription("See and change your MachineEngine settings");
        setPermission(PERMISSION);
        addOptionalArgument(Commands.argument("arguments", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    CorePlayers players = players();
                    if (players == null) return builder.buildFuture();
                    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                    int space = typed.indexOf(' ');
                    if (space < 0) {
                        for (PlayerSetting<?> setting : visible(players)) {
                            for (String name : List.of(setting.key().asString(), setting.key().value())) {
                                if (name.startsWith(typed)) builder.suggest(name);
                            }
                        }
                    } else {
                        PlayerSetting<?> setting = find(players, typed.substring(0, space));
                        if (setting != null) {
                            String value = typed.substring(space + 1);
                            List<String> values = new ArrayList<>(setting.suggestions());
                            values.add("reset");
                            for (String option : values) {
                                if (option.startsWith(value)) builder.suggest(typed.substring(0, space + 1) + option);
                            }
                        }
                    }
                    return builder.buildFuture();
                }));
    }

    private @Nullable CorePlayers players() {
        CoreMachineEngine running = this.engine.get();
        return running == null ? null : running.players();
    }

    /** Settings players may change. */
    private static List<PlayerSetting<?>> visible(CorePlayers players) {
        return players.settings().stream().filter(setting -> !players.isLocked(setting)).toList();
    }

    /** The unlocked setting named {@code name}: its key, or its name alone when only one setting has it. */
    static @Nullable PlayerSetting<?> find(CorePlayers players, String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        PlayerSetting<?> found = null;
        for (PlayerSetting<?> setting : visible(players)) {
            if (setting.key().asString().equals(wanted)) return setting;
            if (setting.key().value().equals(wanted)) {
                if (found != null) return null; // ambiguous
                found = setting;
            }
        }
        return found;
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
        if (!(dispatch.getExecutor() instanceof Player player)) {
            dispatch.getSender().sendMessage(Messages.message("machineengine.settings.players_only", "Only players have settings."));
            return CommandResultType.FAILURE;
        }
        CorePlayers players = players();
        MachinePlayer data = players == null ? null : players.find(player.getUniqueId()).orElse(null);
        if (data == null) return CommandResultType.FAILURE;
        String arguments = dispatch.getOptionalArgument("arguments", String.class).orElse("").trim();
        if (arguments.isEmpty()) {
            MachineMenuService menus = MachineMenuService.current();
            if (menus != null && menus.openPlayerSettings(player)) return CommandResultType.SUCCESS;
            list(player, players, data);
            return CommandResultType.SUCCESS;
        }
        int space = arguments.indexOf(' ');
        String name = space < 0 ? arguments : arguments.substring(0, space);
        PlayerSetting<?> setting = find(players, name);
        if (setting == null) {
            player.sendMessage(Messages.settingUnknown(name));
            return CommandResultType.FAILURE;
        }
        if (space < 0) {
            player.sendMessage(Messages.settingValue(setting, display(data, setting)));
            return CommandResultType.SUCCESS;
        }
        String value = arguments.substring(space + 1).trim();
        return change(player, players, data, setting, value) ? CommandResultType.SUCCESS : CommandResultType.FAILURE;
    }

    private static void list(Player player, CorePlayers players, MachinePlayer data) {
        List<PlayerSetting<?>> settings = visible(players);
        if (settings.isEmpty()) {
            player.sendMessage(Messages.settingsNone());
            return;
        }
        player.sendMessage(Messages.settingsHeader());
        for (PlayerSetting<?> setting : settings) player.sendMessage(Messages.settingEntry(setting, display(data, setting)));
    }

    /** Sets {@code setting} from {@code text} ({@code reset}: back to the default) and tells the player. */
    static boolean change(Player player, CorePlayers players, MachinePlayer data, PlayerSetting<?> setting, String text) {
        if (text.equalsIgnoreCase("reset")) {
            text = format(setting, players.ownerDefault(setting));
            data.reset(setting.data());
            player.sendMessage(Messages.settingChanged(setting, text));
            return true;
        }
        Result<?> result = data.set(setting, text);
        if (result.isErr()) {
            player.sendMessage(result.error().code().equals(CorePlayers.SETTING_LOCKED) ? Messages.settingLocked(setting)
                    : Messages.settingInvalid(text, setting.expected()));
            return false;
        }
        player.sendMessage(Messages.settingChanged(setting, display(data, setting)));
        return true;
    }

    static <T> String display(MachinePlayer data, PlayerSetting<T> setting) {
        return setting.format(data.get(setting));
    }

    @SuppressWarnings("unchecked")
    private static <T> String format(PlayerSetting<T> setting, Object value) {
        return setting.format((T) value);
    }
}
