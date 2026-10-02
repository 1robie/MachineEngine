package fr.robie.machineengine.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
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
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code /machineengine} admin commands (§27): status, startup report, machine inspection, registry
 * listing and reloads. Debug output must be useful without attaching a debugger.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MachineEngineCommands {
    public static final String PERMISSION = "machineengine.admin";

    private final org.bukkit.plugin.Plugin plugin;
    private final Supplier<CoreMachineEngine> engine;
    private final Supplier<MachineLocator> locator;
    private final MachineDebugView debugView;

    public MachineEngineCommands(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull Supplier<CoreMachineEngine> engine,
                                 @NotNull Supplier<MachineLocator> locator) {
        this.plugin = plugin;
        this.engine = engine;
        this.locator = locator;
        this.debugView = new MachineDebugView(plugin, engine, locator);
    }

    /** The in-world debug view; the plugin stops it on disable. */
    public @NotNull MachineDebugView debugView() {
        return this.debugView;
    }

    public @NotNull LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("machineengine")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("status").executes(this::status))
                .then(Commands.literal("report").executes(this::report))
                .then(Commands.literal("debug").executes(this::debug))
                // Positions are optional: a player defaults to the block they look at; "~" works.
                .then(PositionArguments.optionalPosition(Commands.literal("inspect"), PositionArguments.Default.TARGET_BLOCK, this::inspectAt))
                .then(PositionArguments.optionalPosition(Commands.literal("machine"), PositionArguments.Default.TARGET_BLOCK, this::inspectAt))
                .then(PositionArguments.optionalPosition(Commands.literal("placeholders"), PositionArguments.Default.TARGET_BLOCK, this::placeholders)
                        .then(Commands.literal("list").executes(this::listPlaceholders))
                        .then(PositionArguments.optionalPosition(Commands.literal("purge"), PositionArguments.Default.TARGET_BLOCK, this::purge)))
                .then(PositionArguments.optionalPosition(Commands.literal("network"), PositionArguments.Default.TARGET_BLOCK, this::network))
                .then(Commands.literal("registry")
                        .then(Commands.argument("registry", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    CoreMachineEngine running = this.engine.get();
                                    if (running != null) running.registries().types().forEach(type -> builder.suggest(type.key().asString()));
                                    return builder.buildFuture();
                                })
                                .executes(this::registry)))
                .then(Commands.literal("reload")
                        .then(Commands.literal("recipes").executes(context -> reload(context, Registries.RECIPE)))
                        .then(Commands.literal("tags").executes(context -> reload(context, Registries.RESOURCE_TAG))))
                .build();
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        CoreMachineEngine running = this.engine.get();
        if (running == null) {
            sender.sendMessage(Component.text("MachineEngine is not running.", NamedTextColor.RED));
            return 0;
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
        return Command.SINGLE_SUCCESS;
    }

    private int debug(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getExecutor() instanceof org.bukkit.entity.Player player)) {
            context.getSource().getSender().sendMessage(Component.text("Only players can see the debug view.", NamedTextColor.RED));
            return 0;
        }
        boolean on = this.debugView.toggle(player);
        player.sendMessage(on ? Messages.debugOn() : Messages.debugOff());
        return Command.SINGLE_SUCCESS;
    }

    private int report(CommandContext<CommandSourceStack> context) {
        CoreMachineEngine running = this.engine.get();
        if (running == null) return 0;
        sendReport(context.getSource().getSender(), running.startupReport());
        return Command.SINGLE_SUCCESS;
    }

    private int inspectAt(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        CommandSender sender = context.getSource().getSender();
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        // Machines are read on the thread that owns them (Folia: their region).
        onRegion(position, world, () -> inspect(sender, position));
        return Command.SINGLE_SUCCESS;
    }

    private int network(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        CommandSender sender = context.getSource().getSender();
        CoreMachineEngine running = this.engine.get();
        if (running == null) {
            sender.sendMessage(Component.text("MachineEngine is not running.", NamedTextColor.RED));
            return 0;
        }
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        onRegion(position, world, () -> describeNetwork(sender, running, position));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Loaded machines holding data of missing content: placeholders (addon missing) and machines with
     * locked slots or unknown data. Unloaded chunks are not scanned.
     */
    private int listPlaceholders(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
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
        return Command.SINGLE_SUCCESS;
    }

    private final Map<String, PendingPurge> pendingPurges = new java.util.concurrent.ConcurrentHashMap<>();

    private record PendingPurge(WorldPosition position, long until) {
    }

    /** Irreversible, so it must be run twice within 10 seconds. */
    private int purge(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        CommandSender sender = context.getSource().getSender();
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        PendingPurge pending = this.pendingPurges.get(sender.getName());
        long now = System.currentTimeMillis();
        if (pending == null || !pending.position().equals(position) || pending.until() < now) {
            this.pendingPurges.put(sender.getName(), new PendingPurge(position, now + 10_000));
            sender.sendMessage(Component.text("This deletes the missing content's data at " + position
                    + " for good (a placeholder machine is removed with its block). Run the command again within 10 seconds to confirm.",
                    NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }
        this.pendingPurges.remove(sender.getName());
        onRegion(position, world, () -> {
            String result = this.locator.get().purge(position);
            sender.sendMessage(result == null ? Component.text("Nothing to purge at " + position + ".", NamedTextColor.GRAY)
                    : Component.text("Purged: " + result + ".", NamedTextColor.GREEN));
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Prints the menu placeholders of a machine, e.g. to check a menu without opening it. */
    private int placeholders(CommandContext<CommandSourceStack> context, World world, int x, int y, int z) {
        CommandSender sender = context.getSource().getSender();
        WorldPosition position = WorldPosition.of(world.getKey(), x, y, z);
        onRegion(position, world, () -> {
            LoadedMachine machine = this.locator.get().machine(position);
            if (machine == null) {
                sender.sendMessage(Component.text("No machine is loaded at " + position + ".", NamedTextColor.RED));
                return;
            }
            MachineInspection inspection = machine.inspect();
            List<String> keys = new java.util.ArrayList<>(List.of("type", "state", "facing", "process_status", "process_bar",
                    "process_percent", "process_remaining_seconds", "upgrades"));
            inspection.storages().keySet().forEach(id -> keys.addAll(List.of("storage_" + id + "_amount",
                    "storage_" + id + "_capacity", "storage_" + id + "_bar", "storage_" + id + "_resource")));
            for (String key : keys) {
                sender.sendMessage(line("%zmenu_machineengine_" + key + "%", String.valueOf(MachinePlaceholders.resolve(inspection, key))));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private void onRegion(WorldPosition position, World world, Runnable task) {
        Bukkit.getRegionScheduler().execute(this.plugin, world, position.position().chunkX(), position.position().chunkZ(), task);
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
        List<String> lines = new java.util.ArrayList<>();
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

    private int registry(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        CoreMachineEngine running = this.engine.get();
        if (running == null) return 0;
        String name = StringArgumentType.getString(context, "registry").trim();
        Key key = Key.key(name.contains(":") ? name : "machineengine:" + name);
        Registry<?> registry = running.registries().find(key).orElse(null);
        if (registry == null) {
            sender.sendMessage(Component.text("No registry " + key.asString() + ".", NamedTextColor.RED));
            return 0;
        }
        sender.sendMessage(Component.text("Registry " + key.asString() + " (" + registry.size() + " entries)", NamedTextColor.GOLD));
        for (Key entry : registry.keys()) {
            sender.sendMessage(line(entry.asString(), registry.owner(entry).map(Object::toString).orElse("?")));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> context, RegistryType<?> type) {
        CommandSender sender = context.getSource().getSender();
        CoreMachineEngine running = this.engine.get();
        if (running == null) return 0;
        ValidationReport report = running.reload(type);
        if (report.hasErrors()) {
            sender.sendMessage(Component.text("Reload of " + type + " rejected; the previous content stays live.", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("Reloaded " + type + ".", NamedTextColor.GREEN));
            if (type.equals(Registries.RECIPE)) this.locator.get().wakeAll(WakeReason.RECIPES_RELOADED);
        }
        sendReport(sender, report);
        return report.hasErrors() ? 0 : Command.SINGLE_SUCCESS;
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
