package fr.robie.machineengine.paper.player;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.player.CorePlayers;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /machineengine player <player> [get <key> | set <key> <value> | reset <key>]} (D37): an admin
 * reads or changes any value a player has, settings included (a lock does not stop an admin). Without an
 * action it lists them all. Offline players work with a storage that can read them ({@code machineengine:files}).
 */
@SuppressWarnings("UnstableApiUsage")
public final class PlayerAdminCommand extends SubCommand<Plugin> {
    private static final String USAGE = "Usage: /machineengine player <player> [get <key> | set <key> <value> | reset <key>]";
    private final Supplier<CoreMachineEngine> engine;

    public PlayerAdminCommand(@NotNull Plugin plugin, @NotNull Supplier<CoreMachineEngine> engine) {
        super(plugin, "player");
        this.engine = engine;
        addRequiredArgument(Commands.argument("arguments", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    CoreMachineEngine running = engine.get();
                    String typed = builder.getRemaining();
                    String[] words = typed.split(" ", -1);
                    String before = typed.substring(0, typed.length() - words[words.length - 1].length());
                    String last = words[words.length - 1].toLowerCase(Locale.ROOT);
                    List<String> options = switch (words.length) {
                        case 1 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                        case 2 -> List.of("get", "set", "reset");
                        case 3 -> running == null ? List.of() : running.players().keys().stream().map(key -> key.key().asString()).toList();
                        default -> List.of();
                    };
                    for (String option : options) {
                        if (option.toLowerCase(Locale.ROOT).startsWith(last)) builder.suggest(before + option);
                    }
                    return builder.buildFuture();
                }));
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
        CommandSender sender = dispatch.getSender();
        CoreMachineEngine running = this.engine.get();
        if (running == null) return CommandResultType.FAILURE;
        CorePlayers players = running.players();
        String[] words = dispatch.getArgument("arguments", String.class).trim().split(" +", 4);
        String action = words.length > 1 ? words[1].toLowerCase(Locale.ROOT) : "list";
        if (!List.of("list", "get", "set", "reset").contains(action) || (!action.equals("list") && words.length < 3)
                || (action.equals("set") && words.length < 4)) {
            sender.sendMessage(Component.text(USAGE, NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        PlayerDataKey<?> key = action.equals("list") ? null : PlayerValues.key(players, words[2]);
        if (!action.equals("list") && key == null) {
            sender.sendMessage(Component.text("No player data key " + words[2] + ". Keys: "
                    + players.keys().stream().map(k -> k.key().asString()).toList(), NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        String value = words.length > 3 ? words[3] : null;

        Player online = Bukkit.getPlayerExact(words[0]);
        if (online != null) {
            MachineEngine.scheduler(this.plugin).run(online, () -> players.find(online.getUniqueId())
                    .ifPresent(data -> act(sender, players, data, online.getName(), action, key, value)), null);
            return CommandResultType.SUCCESS;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(words[0]);
        if (offline == null) {
            sender.sendMessage(Component.text(words[0] + " has never played here.", NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        String name = offline.getName() != null ? offline.getName() : words[0];
        players.loadOffline(offline.getUniqueId()).whenComplete((loaded, error) -> {
            if (error != null) {
                sender.sendMessage(Component.text("Could not load the data of " + name + ": " + error.getMessage(), NamedTextColor.RED));
            } else if (loaded.isEmpty()) {
                sender.sendMessage(Component.text(name + " is offline, and the player data storage cannot read offline players"
                        + " (players.storage: machineengine:files can).", NamedTextColor.RED));
            } else if (act(sender, players, loaded.get(), name + " (offline)", action, key, value) && !action.equals("get") && !action.equals("list")) {
                players.saveOffline(loaded.get()).whenComplete((saved, saveError) -> {
                    if (saveError != null) sender.sendMessage(Component.text("Could not save: " + saveError.getMessage(), NamedTextColor.RED));
                });
            }
        });
        return CommandResultType.SUCCESS;
    }

    /** @return whether it worked */
    private static boolean act(CommandSender sender, CorePlayers players, MachinePlayer data, String name, String action,
                               PlayerDataKey<?> key, String value) {
        switch (action) {
            case "get" -> sender.sendMessage(line(name + " " + key.key().asString(), PlayerValues.text(data, key)));
            case "set" -> {
                String problem = PlayerValues.set(players, data, key, value);
                if (problem != null) {
                    sender.sendMessage(Component.text(problem + ".", NamedTextColor.RED));
                    return false;
                }
                sender.sendMessage(Component.text("Set " + key.key().asString() + " of " + name + " to " + PlayerValues.text(data, key) + ".",
                        NamedTextColor.GREEN));
            }
            case "reset" -> {
                data.reset(key);
                sender.sendMessage(Component.text("Reset " + key.key().asString() + " of " + name + ".", NamedTextColor.GREEN));
            }
            default -> list(sender, players, data, name);
        }
        return true;
    }

    private static void list(CommandSender sender, CorePlayers players, MachinePlayer data, String name) {
        sender.sendMessage(Component.text("Player data of " + name + " (storage " + storageName(players) + ")", NamedTextColor.GOLD));
        for (PlayerDataKey<?> key : players.keys()) {
            boolean setting = players.setting(key.key()) != null;
            String shown = setting ? PlayerValues.settingText(data, players.setting(key.key())) : PlayerValues.text(data, key);
            String kind = setting ? (players.isLocked(players.setting(key.key())) ? "setting, locked" : "setting")
                    : key.isPersistent() ? "saved" : "session";
            sender.sendMessage(line("  " + key.key().asString(), shown + " (" + kind + ")"));
        }
        for (Key unknown : data.unknownKeys()) {
            sender.sendMessage(line("  " + unknown.asString(), "kept, not registered (addon missing?)"));
        }
    }

    private static String storageName(CorePlayers players) {
        return players.storage().getClass().getSimpleName();
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
