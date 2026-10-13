package fr.robie.machineengine.paper.player;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.player.CorePlayers;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
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
            EngineMessages.send(sender, EngineMessage.ADMIN_USAGE, Placeholder.of("usage", USAGE));
            return CommandResultType.FAILURE;
        }
        PlayerDataKey<?> key = action.equals("list") ? null : PlayerValues.key(players, words[2]);
        if (!action.equals("list") && key == null) {
            EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_NO_KEY,
                    Placeholder.of("key", words[2], "keys", String.valueOf(players.keys().stream().map(k -> k.key().asString()).toList())));
            return CommandResultType.FAILURE;
        }
        String value = words.length > 3 ? words[3] : null;

        Player online = Bukkit.getPlayerExact(words[0]);
        if (online != null) {
            MachineEngine.scheduler(this.plugin).run(online, () -> players.find(online.getUniqueId())
                    .ifPresent(data -> act(sender, players, data, Component.text(online.getName()), action, key, value)), null);
            return CommandResultType.SUCCESS;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(words[0]);
        if (offline == null) {
            EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_NEVER_PLAYED, Placeholder.of("player", words[0]));
            return CommandResultType.FAILURE;
        }
        String name = offline.getName() != null ? offline.getName() : words[0];
        players.loadOffline(offline.getUniqueId()).whenComplete((loaded, error) -> {
            if (error != null) {
                EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_LOAD_FAILED, Placeholder.of("player", name, "error", error.getMessage()));
            } else if (loaded.isEmpty()) {
                EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_OFFLINE_UNREADABLE, Placeholder.of("player", name));
            } else if (act(sender, players, loaded.get(), EngineMessages.component(EngineMessage.ADMIN_PLAYERS_OFFLINE_NAME,
                    sender instanceof Player viewer ? viewer : null, Placeholder.of("player", name)), action, key, value) && !action.equals("get") && !action.equals("list")) {
                players.saveOffline(loaded.get()).whenComplete((saved, saveError) -> {
                    if (saveError != null) {
                        EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_SAVE_FAILED, Placeholder.of("error", saveError.getMessage()));
                    }
                });
            }
        });
        return CommandResultType.SUCCESS;
    }

    /** @return whether it worked */
    private static boolean act(CommandSender sender, CorePlayers players, MachinePlayer data, Component name, String action,
                               PlayerDataKey<?> key, String value) {
        switch (action) {
            case "get" -> EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_GET, Placeholder.builder()
                    .register("player", name).register("key", key.key().asString()).register("value", PlayerValues.text(data, key)).build());
            case "set" -> {
                String problem = PlayerValues.set(players, data, key, value);
                if (problem != null) {
                    EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_SET_PROBLEM, Placeholder.of("problem", problem));
                    return false;
                }
                EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_SET, Placeholder.builder()
                        .register("player", name).register("key", key.key().asString()).register("value", PlayerValues.text(data, key)).build());
            }
            case "reset" -> {
                data.reset(key);
                EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_RESET,
                        Placeholder.builder().register("player", name).register("key", key.key().asString()).build());
            }
            default -> list(sender, players, data, name);
        }
        return true;
    }

    private static void list(CommandSender sender, CorePlayers players, MachinePlayer data, Component name) {
        EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_LIST,
                Placeholder.builder().register("player", name).register("storage", storageName(players)).build());
        for (PlayerDataKey<?> key : players.keys()) {
            boolean setting = players.setting(key.key()) != null;
            String shown = setting ? PlayerValues.settingText(data, players.setting(key.key())) : PlayerValues.text(data, key);
            EngineMessage kind = setting ? (players.isLocked(players.setting(key.key())) ? EngineMessage.ADMIN_PLAYERS_ENTRY_LOCKED
                    : EngineMessage.ADMIN_PLAYERS_ENTRY_SETTING)
                    : key.isPersistent() ? EngineMessage.ADMIN_PLAYERS_ENTRY_SAVED : EngineMessage.ADMIN_PLAYERS_ENTRY_SESSION;
            EngineMessages.send(sender, kind, Placeholder.of("key", key.key().asString(), "value", shown));
        }
        for (Key unknown : data.unknownKeys()) {
            EngineMessages.send(sender, EngineMessage.ADMIN_PLAYERS_ENTRY_UNKNOWN, Placeholder.of("key", unknown.asString()));
        }
    }

    private static String storageName(CorePlayers players) {
        return players.storage().getClass().getSimpleName();
    }
}
