package fr.robie.machineengine.paper;

import com.mojang.brigadier.arguments.StringArgumentType;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.paper.PositionArguments;
import fr.robie.machineengine.api.config.ConfigLoadResult;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import fr.robie.machineengine.paper.player.PlayerAdminCommand;
import fr.robie.paperdispatch.command.BaseCommand;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /machineengine} admin commands (§27): status, startup report, machine inspection, registry
 * listing and reloads. Debug output must be useful without attaching a debugger. Built with
 * Paper-Dispatch (D35).
 */
@SuppressWarnings("UnstableApiUsage")
public final class MachineEngineCommands extends BaseCommand<Plugin> {
    public static final String PERMISSION = "machineengine.admin";
    private static final String USAGE = "Usage: /machineengine <status|report|debug|inspect|fill|drain|wake|placeholders|network|registry|player|reload|check>";

    private final Supplier<CoreMachineEngine> engine;
    private final Supplier<MachineLocator> locator;
    private final Supplier<ConfigLoadResult> reloadConfig;
    private final MachineDebugView debugView;

    public MachineEngineCommands(@NotNull Plugin plugin, @NotNull Supplier<CoreMachineEngine> engine,
                                 @NotNull Supplier<MachineLocator> locator, @NotNull Supplier<ConfigLoadResult> reloadConfig) {
        super(plugin, "machineengine");
        this.engine = engine;
        this.locator = locator;
        this.reloadConfig = reloadConfig;
        this.debugView = new MachineDebugView(plugin, engine, locator);
        setDescription("MachineEngine admin commands");
        setPermission(PERMISSION);
        addSubCommand(simple("status", this::status));
        addSubCommand(simple("report", this::report));
        addSubCommand(simple("debug", this::debug));
        // Positions are optional: a player defaults to the block they look at; "~" works.
        addSubCommand(new PositionCommand(plugin, "inspect", this::inspectAt, "machine"));
        addSubCommand(new PositionCommand(plugin, "placeholders", this::placeholders, null,
                simple("list", this::listPlaceholders),
                new PositionCommand(plugin, "purge", this::purge, null)));
        addSubCommand(new PositionCommand(plugin, "network", this::network, null));
        addSubCommand(new RegistryCommand(plugin, this.engine, this::registry));
        addSubCommand(new PlayerAdminCommand(plugin, this.engine));
        MachineStorageCommands.commands(plugin, this.engine, this.locator).forEach(this::addSubCommand);
        addSubCommand(EngineChecks.command(plugin));
        addSubCommand(SubCommand.builder(plugin, "reload")
                .addSubCommand(simple("config", dispatch -> reloadConfig(dispatch.getSender())))
                .addSubCommand(simple("recipes", dispatch -> reload(dispatch.getSender(), Registries.RECIPE)))
                .addSubCommand(simple("tags", dispatch -> {
                    reload(dispatch.getSender(), Registries.ITEM_TAG);
                    return reload(dispatch.getSender(), Registries.RESOURCE_TAG);
                }))
                .executes(dispatch -> usage(dispatch, "Usage: /machineengine reload <config|recipes|tags>"))
                .build());
    }

    /** The in-world debug view; the plugin stops it on disable. */
    public @NotNull MachineDebugView debugView() {
        return this.debugView;
    }

    @Override
    protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
        return usage(dispatch, USAGE);
    }

    private static CommandResultType usage(CommandDispatch<Plugin> dispatch, String usage) {
        dispatch.getSender().sendMessage(Component.text(usage, NamedTextColor.RED));
        return CommandResultType.FAILURE;
    }

    private SubCommand<Plugin> simple(String name, SubCommand.ArgumentExecutor<Plugin> executor) {
        return SubCommand.builder(this.plugin, name).executes(executor).build();
    }

    /** What a command at a position does, once the world and block coordinates are resolved. */
    @FunctionalInterface
    private interface PositionHandler {
        @NotNull CommandResultType run(@NotNull CommandSender sender, @NotNull World world, int x, int y, int z);
    }

    /** A sub-command taking {@code [<x y z>] [<world>]}, the block looked at by default ({@link PositionArguments}). */
    private static final class PositionCommand extends SubCommand<Plugin> {
        private final PositionHandler handler;

        @SafeVarargs
        PositionCommand(Plugin plugin, String name, PositionHandler handler, @Nullable String alias, SubCommand<Plugin>... children) {
            super(plugin, name, alias == null ? new String[0] : new String[]{alias});
            this.handler = handler;
            for (SubCommand<Plugin> child : children) addSubCommand(child);
            addOptionalArgument(PositionArguments.positionArgument());
            addOptionalArgument(PositionArguments.worldArgument());
        }

        @Override
        protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
            PositionArguments.Position position = PositionArguments.resolve(dispatch.getContext(), PositionArguments.Default.TARGET_BLOCK);
            if (position == null) return CommandResultType.FAILURE;
            return this.handler.run(dispatch.getSender(), position.world(), position.x(), position.y(), position.z());
        }
    }

    /** {@code registry <registry>}, with the registries suggested. */
    private static final class RegistryCommand extends SubCommand<Plugin> {
        private final Function<CommandDispatch<Plugin>, CommandResultType> executor;

        RegistryCommand(Plugin plugin, Supplier<CoreMachineEngine> engine, Function<CommandDispatch<Plugin>, CommandResultType> executor) {
            super(plugin, "registry");
            this.executor = executor;
            addRequiredArgument(Commands.argument("registry", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        CoreMachineEngine running = engine.get();
                        if (running != null) running.registries().types().forEach(type -> builder.suggest(type.key().asString()));
                        return builder.buildFuture();
                    }));
        }

        @Override
        protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
            return this.executor.apply(dispatch);
        }
    }

    private CommandResultType status(CommandDispatch<Plugin> dispatch) {
        CommandSender sender = dispatch.getSender();
        CoreMachineEngine running = this.engine.get();
        if (running == null) {
            sender.sendMessage(Component.text("MachineEngine is not running.", NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        List<LoadedMachine> machines = List.copyOf(this.locator.get().loadedMachines());
        long active = machines.stream().filter(machine -> machine.instance() != null
                && machine.instance().schedulerState() == SchedulerState.ACTIVE).count();
        long placeholders = machines.stream().filter(machine -> machine.instance() == null).count();
        sender.sendMessage(Component.text("MachineEngine " + running.phase(), NamedTextColor.GOLD));
        sender.sendMessage(line("Enabled addons", running.enabledAddons().toString()));
        sender.sendMessage(line("Disabled addons", running.disabledAddons().toString()));
        sender.sendMessage(line("Loaded machines", machines.size() + " (" + active + " active, "
                + (machines.size() - active - placeholders) + " sleeping, " + placeholders + " placeholders)"));
        sender.sendMessage(line("Startup problems", running.startupReport().errors().size() + " errors, "
                + running.startupReport().warnings().size() + " warnings"));
        return CommandResultType.SUCCESS;
    }

    private CommandResultType debug(CommandDispatch<Plugin> dispatch) {
        if (!(dispatch.getExecutor() instanceof Player player)) {
            dispatch.getSender().sendMessage(Component.text("Only players can see the debug view.", NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        boolean on = this.debugView.toggle(player);
        EngineMessages.send(player, on ? EngineMessage.DEBUG_ON : EngineMessage.DEBUG_OFF);
        return CommandResultType.SUCCESS;
    }

    private CommandResultType report(CommandDispatch<Plugin> dispatch) {
        CoreMachineEngine running = this.engine.get();
        if (running == null) return CommandResultType.FAILURE;
        sendReport(dispatch.getSender(), running.startupReport());
        return CommandResultType.SUCCESS;
    }

    private CommandResultType inspectAt(CommandSender sender, World world, int x, int y, int z) {
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        // Machines are read on the thread that owns them (Folia: their region).
        onRegion(position, world, () -> inspect(sender, position));
        return CommandResultType.SUCCESS;
    }

    private CommandResultType network(CommandSender sender, World world, int x, int y, int z) {
        CoreMachineEngine running = this.engine.get();
        if (running == null) {
            sender.sendMessage(Component.text("MachineEngine is not running.", NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        onRegion(position, world, () -> describeNetwork(sender, running, position));
        return CommandResultType.SUCCESS;
    }

    /**
     * Loaded machines holding data of missing content: placeholders (addon missing) and machines with
     * locked slots or unknown data. Unloaded chunks are not scanned.
     */
    private CommandResultType listPlaceholders(CommandDispatch<Plugin> dispatch) {
        CommandSender sender = dispatch.getSender();
        List<LoadedMachine> found = this.locator.get().loadedMachines().stream()
                .filter(machine -> {
                    MachineInspection inspection = machine.inspect();
                    return inspection.placeholder() || !inspection.lockedSlots().isEmpty() || !inspection.unknownData().isEmpty();
                }).toList();
        sender.sendMessage(Component.text(found.size() + " loaded machine(s) keep data of missing content"
                + (found.isEmpty() ? "." : ":"), NamedTextColor.GOLD));
        for (LoadedMachine machine : found) {
            MachineInspection inspection = machine.inspect();
            String what = inspection.placeholder() ? "placeholder (" + inspection.type().asString() + " is not registered)"
                    : inspection.type().asString() + ": locked slots " + inspection.lockedSlots() + ", unknown data " + inspection.unknownData();
            sender.sendMessage(line("  " + machine.position(), what));
        }
        if (!found.isEmpty()) {
            sender.sendMessage(Component.text("Delete with /machineengine placeholders purge <x y z> [world] (irreversible).", NamedTextColor.GRAY));
        }
        return CommandResultType.SUCCESS;
    }

    private final Map<String, PendingPurge> pendingPurges = new ConcurrentHashMap<>();

    private record PendingPurge(WorldPosition position, long until) {
    }

    /** Irreversible, so it must be run twice within 10 seconds. */
    private CommandResultType purge(CommandSender sender, World world, int x, int y, int z) {
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        PendingPurge pending = this.pendingPurges.get(sender.getName());
        long now = System.currentTimeMillis();
        if (pending == null || !pending.position().equals(position) || pending.until() < now) {
            this.pendingPurges.put(sender.getName(), new PendingPurge(position, now + 10_000));
            sender.sendMessage(Component.text("This deletes the missing content's data at " + position
                    + " for good (a placeholder machine is removed with its block). Run the command again within 10 seconds to confirm.",
                    NamedTextColor.YELLOW));
            return CommandResultType.SUCCESS;
        }
        this.pendingPurges.remove(sender.getName());
        onRegion(position, world, () -> {
            String result = this.locator.get().purge(position);
            sender.sendMessage(result == null ? Component.text("Nothing to purge at " + position + ".", NamedTextColor.GRAY)
                    : Component.text("Purged: " + result + ".", NamedTextColor.GREEN));
        });
        return CommandResultType.SUCCESS;
    }

    /** Prints the menu placeholders of a machine, e.g. to check a menu without opening it. */
    private CommandResultType placeholders(CommandSender sender, World world, int x, int y, int z) {
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        onRegion(position, world, () -> {
            LoadedMachine machine = this.locator.get().machine(position);
            if (machine == null) {
                sender.sendMessage(Component.text("No machine is loaded at " + position + ".", NamedTextColor.RED));
                return;
            }
            MachineInspection inspection = machine.inspect();
            List<String> keys = new ArrayList<>(List.of("type", "state", "facing", "process_status", "process_bar",
                    "process_percent", "process_remaining_seconds", "upgrades"));
            inspection.storages().keySet().forEach(id -> keys.addAll(List.of("storage_" + id + "_amount",
                    "storage_" + id + "_capacity", "storage_" + id + "_bar", "storage_" + id + "_resource")));
            for (String key : keys) {
                sender.sendMessage(line("%zmenu_machineengine_" + key + "%", String.valueOf(MachinePlaceholders.resolve(inspection, key))));
            }
        });
        return CommandResultType.SUCCESS;
    }

    private void onRegion(WorldPosition position, World world, Runnable task) {
        MachineEngine.scheduler(this.plugin).run(world, position.position().chunkX(), position.position().chunkZ(), task);
    }

    private void describeNetwork(CommandSender sender, CoreMachineEngine running, WorldPosition position) {
        var network = running.machines().networks().network(position).orElse(null);
        if (network == null) {
            sender.sendMessage(Component.text("No loaded transport at " + position + ".", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Network " + network.type().key().asString() + ": " + network.transports().size()
                + " transports, rate " + network.ratePerTick() + "/tick, " + (network.active() ? "active" : "sleeping")
                + ", moved " + network.movedLastTick() + " last tick", NamedTextColor.GOLD));
        network.endpoints().forEach(endpoint -> sender.sendMessage(line("  endpoint", endpoint)));
    }

    private void inspect(CommandSender sender, WorldPosition position) {
        LoadedMachine machine = this.locator.get().machine(position);
        if (machine == null) {
            sender.sendMessage(Component.text("No machine is loaded at " + position + ".", NamedTextColor.RED));
            return;
        }
        for (String line : describe(machine.inspect())) sender.sendMessage(Component.text(line));
    }

    /** Human-readable inspection; also used in logs. */
    public static @NotNull List<String> describe(@NotNull MachineInspection inspection) {
        List<String> lines = new ArrayList<>();
        lines.add("Machine " + inspection.type().asString() + (inspection.placeholder() ? " (PLACEHOLDER: type not installed)" : "")
                + " at " + inspection.position() + " facing " + inspection.facing());
        lines.add("  state: " + inspection.state() + (inspection.lastWakeReason() == null ? "" : ", last woken by " + inspection.lastWakeReason()));
        if (inspection.lastError() != null) lines.add("  last error: " + inspection.lastError().format());
        for (Map.Entry<String, List<StorageView>> storage : inspection.storages().entrySet()) {
            StringBuilder contents = new StringBuilder();
            for (StorageView view : storage.getValue()) {
                if (contents.length() > 0) contents.append(", ");
                contents.append(view.isEmpty() ? "empty" : view.amount() + " " + view.variant()).append(" / ").append(view.capacity());
            }
            lines.add("  storage " + storage.getKey() + ": " + contents);
        }
        if (inspection.process() != null) {
            var process = inspection.process();
            lines.add("  process " + process.recipe().asString() + ": " + process.status() + " " + process.progress() + "/"
                    + process.duration() + " ticks, in flight " + process.inFlight());
        }
        if (!inspection.upgrades().isEmpty()) lines.add("  upgrades: " + inspection.upgrades());
        if (!inspection.escrow().isEmpty()) lines.add("  in escrow: " + inspection.escrow());
        if (!inspection.lockedSlots().isEmpty()) lines.add("  locked slots (resource type not installed): " + inspection.lockedSlots());
        if (!inspection.unknownData().isEmpty()) lines.add("  kept unknown data: " + inspection.unknownData());
        return lines;
    }

    private CommandResultType registry(CommandDispatch<Plugin> dispatch) {
        CommandSender sender = dispatch.getSender();
        CoreMachineEngine running = this.engine.get();
        if (running == null) return CommandResultType.FAILURE;
        String name = dispatch.getArgument("registry", String.class).trim();
        Key key = Key.key(name.contains(":") ? name : "machineengine:" + name);
        Registry<?> registry = running.registries().find(key).orElse(null);
        if (registry == null) {
            sender.sendMessage(Component.text("No registry " + key.asString() + ".", NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        sender.sendMessage(Component.text("Registry " + key.asString() + " (" + registry.size() + " entries)", NamedTextColor.GOLD));
        for (Key entry : registry.keys()) {
            sender.sendMessage(line(entry.asString(), registry.owner(entry).map(Object::toString).orElse("?")));
        }
        registry.aliases().forEach((from, to) -> sender.sendMessage(line(from.asString() + " → " + to.asString(),
                "alias by " + registry.owner(from).map(Object::toString).orElse("?"))));
        return CommandResultType.SUCCESS;
    }

    private CommandResultType reloadConfig(CommandSender sender) {
        ConfigLoadResult result = this.reloadConfig.get();
        if (result.error() != null) {
            sender.sendMessage(Component.text("config.yml could not be read; the previous settings stay live: " + result.error(), NamedTextColor.RED));
            return CommandResultType.FAILURE;
        }
        sender.sendMessage(Component.text("Reloaded config.yml.", NamedTextColor.GREEN));
        if (!result.changed().isEmpty()) sender.sendMessage(line("Changed", String.join(", ", result.changed())));
        if (!result.added().isEmpty()) sender.sendMessage(line("Added with defaults", String.join(", ", result.added())));
        if (!result.restartRequired().isEmpty()) {
            sender.sendMessage(Component.text("Restart to apply: " + String.join(", ", result.restartRequired()), NamedTextColor.YELLOW));
        }
        if (!result.invalid().isEmpty()) {
            sender.sendMessage(Component.text("Invalid, using the default (see the console): " + String.join(", ", result.invalid()), NamedTextColor.YELLOW));
        }
        return CommandResultType.SUCCESS;
    }

    private CommandResultType reload(CommandSender sender, RegistryType<?> type) {
        CoreMachineEngine running = this.engine.get();
        if (running == null) return CommandResultType.FAILURE;
        ValidationReport report = running.reload(type);
        if (report.hasErrors()) {
            sender.sendMessage(Component.text("Reload of " + type + " rejected; the previous content stays live.", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("Reloaded " + type + ".", NamedTextColor.GREEN));
            if (type.equals(Registries.RECIPE)) this.locator.get().wakeAll(WakeReason.RECIPES_RELOADED);
        }
        sendReport(sender, report);
        return report.hasErrors() ? CommandResultType.FAILURE : CommandResultType.SUCCESS;
    }

    private static void sendReport(CommandSender sender, ValidationReport report) {
        if (report.diagnostics().isEmpty()) {
            sender.sendMessage(Component.text("No problems found.", NamedTextColor.GREEN));
            return;
        }
        for (Diagnostic diagnostic : report.diagnostics()) {
            NamedTextColor color = diagnostic.isError() ? NamedTextColor.RED : NamedTextColor.YELLOW;
            sender.sendMessage(Component.text(diagnostic.format(), color));
        }
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
