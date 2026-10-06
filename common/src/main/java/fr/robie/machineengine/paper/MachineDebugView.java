package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.network.NetworkView;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code /machineengine debug}: shows machine internals in the world, to the toggling player only
 * (§27). Every half second, for machines and transports around the player:
 *
 * <ul>
 *   <li>a dot above each machine colored by its scheduler state: green active, yellow scheduled,
 *       aqua waiting, orange blocked, blue sleeping, gray disabled; red when it has an error,
 *       magenta for a placeholder (addon missing);</li>
 *   <li>a dot on each port face: blue input, orange output, purple both;</li>
 *   <li>a dot in each transport, one color per network; sparks where the network moved resources
 *       last tick;</li>
 *   <li>an action bar for the machine the player looks at: type, state, process progress, storages,
 *       last wake reason and error.</li>
 * </ul>
 *
 * Runs on the player's thread and only reads machines that thread owns (Folia: its region).
 */
public final class MachineDebugView {
    private static final int PERIOD_TICKS = 10;
    private static final int RADIUS = 16;
    private static final Color INPUT = Color.fromRGB(0x3a7bff);
    private static final Color OUTPUT = Color.fromRGB(0xff8a1f);
    private static final Color BOTH = Color.fromRGB(0xb04cff);

    private final Plugin plugin;
    private final Supplier<CoreMachineEngine> engine;
    private final Supplier<MachineLocator> locator;
    private final Map<UUID, ScheduledTask> viewers = new ConcurrentHashMap<>();

    public MachineDebugView(@NotNull Plugin plugin, @NotNull Supplier<CoreMachineEngine> engine, @NotNull Supplier<MachineLocator> locator) {
        this.plugin = plugin;
        this.engine = engine;
        this.locator = locator;
    }

    /** @return whether the view is now on for {@code player} */
    public boolean toggle(@NotNull Player player) {
        ScheduledTask running = this.viewers.remove(player.getUniqueId());
        if (running != null) {
            running.cancel();
            player.sendActionBar(Component.empty());
            return false;
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(this.plugin, scheduled -> show(player), () -> this.viewers.remove(player.getUniqueId()),
                1, PERIOD_TICKS);
        if (task == null) return false;
        this.viewers.put(player.getUniqueId(), task);
        return true;
    }

    public void stopAll() {
        this.viewers.values().forEach(ScheduledTask::cancel);
        this.viewers.clear();
    }

    private void show(Player player) {
        CoreMachineEngine running = this.engine.get();
        MachineLocator machines = this.locator.get();
        if (running == null || machines == null) return;
        World world = player.getWorld();
        Location eye = player.getLocation();
        for (LoadedMachine machine : machines.loadedMachines()) {
            WorldPosition position = machine.position();
            if (!near(world, eye, position) || !machines.isOwnedByCurrentThread(position)) continue;
            MachineInspection inspection = machine.inspect();
            dot(player, world, position.position(), 0.5, 1.15, 0.5, stateColor(inspection), 1.3f);
            MachineInstanceImpl instance = machine.instance();
            if (instance != null) ports(player, world, instance);
        }
        for (NetworkView network : running.machines().networks().networks(world.getKey())) {
            Color color = networkColor(network);
            for (WorldPosition transport : network.transports()) {
                if (!near(world, eye, transport) || !machines.isOwnedByCurrentThread(transport)) continue;
                BlockPosition at = transport.position();
                dot(player, world, at, 0.5, 0.5, 0.5, color, 0.9f);
                if (network.movedLastTick() > 0) {
                    player.spawnParticle(Particle.ELECTRIC_SPARK, at.x() + 0.5, at.y() + 0.5, at.z() + 0.5, 2, 0.15, 0.15, 0.15, 0);
                }
            }
        }
        actionBar(player, machines);
    }

    private static void ports(Player player, World world, MachineInstanceImpl machine) {
        for (PortDefinition port : machine.type().ports()) {
            if (port.direction() == PortDirection.DISABLED) continue;
            Color color = port.direction() == PortDirection.INPUT ? INPUT : port.direction() == PortDirection.OUTPUT ? OUTPUT : BOTH;
            for (RelativeSide side : port.sides()) {
                Direction face = side.toAbsolute(machine.facing());
                dot(player, world, machine.position().position(), 0.5 + face.dx() * 0.55, 0.5 + face.dy() * 0.55,
                        0.5 + face.dz() * 0.55, color, 0.8f);
            }
        }
    }

    private static void actionBar(Player player, MachineLocator machines) {
        Block target = player.getTargetBlockExact(8);
        if (target == null) return;
        WorldPosition position = WorldPosition.of(target.getWorld().getKey(), target.getX(), target.getY(), target.getZ());
        if (!machines.isOwnedByCurrentThread(position)) return;
        LoadedMachine machine = machines.machine(position);
        if (machine == null) return;
        MachineInspection inspection = machine.inspect();
        Component bar = Component.text(inspection.type().asString(), NamedTextColor.GOLD)
                .append(separator())
                .append(Component.text(inspection.placeholder() ? "PLACEHOLDER" : inspection.state().name(), TextColor.color(stateColor(inspection).asRGB())));
        ProcessView process = inspection.process();
        if (process != null) {
            bar = bar.append(separator()).append(Component.text(process.recipe().value() + " " + process.progress() + "/" + process.duration()
                    + " " + process.status().name().toLowerCase(Locale.ROOT), NamedTextColor.WHITE));
        }
        for (Map.Entry<String, List<StorageView>> storage : inspection.storages().entrySet()) {
            long amount = storage.getValue().stream().mapToLong(StorageView::amount).sum();
            long capacity = storage.getValue().stream().mapToLong(StorageView::capacity).sum();
            bar = bar.append(separator()).append(Component.text(storage.getKey() + " " + amount + "/" + capacity, NamedTextColor.AQUA));
        }
        if (inspection.lastWakeReason() != null) {
            bar = bar.append(separator()).append(Component.text("woke: " + inspection.lastWakeReason(), NamedTextColor.GRAY));
        }
        if (inspection.lastError() != null) {
            bar = bar.append(separator()).append(Component.text(inspection.lastError().message(),
                    inspection.state() == SchedulerState.BLOCKED ? NamedTextColor.GOLD : NamedTextColor.RED));
        }
        player.sendActionBar(bar);
    }

    private static Component separator() {
        return Component.text(" | ", NamedTextColor.DARK_GRAY);
    }

    static Color stateColor(MachineInspection inspection) {
        if (inspection.placeholder()) return Color.fromRGB(0xff3bd4);
        // Blocked is backpressure (shown orange with its reason), not a failure.
        if (inspection.lastError() != null && inspection.state() != SchedulerState.BLOCKED) return Color.fromRGB(0xff2a2a);
        SchedulerState state = inspection.state();
        return switch (state) {
            case ACTIVE -> Color.fromRGB(0x3cff3c);
            case SCHEDULED -> Color.fromRGB(0xffe23b);
            case WAITING -> Color.fromRGB(0x3bffe8);
            case BLOCKED -> Color.fromRGB(0xff8a1f);
            case SLEEPING -> Color.fromRGB(0x3a7bff);
            case DISABLED -> Color.fromRGB(0x8a8a8a);
        };
    }

    /** A stable color per network: from its type and its smallest transport position. */
    private static Color networkColor(NetworkView network) {
        int hash = network.type().key().hashCode();
        WorldPosition anchor = network.transports().stream()
                .min(Comparator.comparingLong(p -> ((long) p.position().x() << 40) ^ ((long) p.position().y() << 20) ^ p.position().z()))
                .orElse(null);
        if (anchor != null) hash = 31 * hash + anchor.hashCode();
        java.awt.Color hsb = java.awt.Color.getHSBColor((hash & 0xffff) / 65536f, 0.75f, 1f);
        return Color.fromRGB(hsb.getRed(), hsb.getGreen(), hsb.getBlue());
    }

    private static boolean near(World world, Location eye, WorldPosition position) {
        if (!position.world().equals(world.getKey())) return false;
        BlockPosition at = position.position();
        double dx = at.x() + 0.5 - eye.getX(), dy = at.y() + 0.5 - eye.getY(), dz = at.z() + 0.5 - eye.getZ();
        return dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS;
    }

    private static void dot(Player player, World world, BlockPosition at, double x, double y, double z, Color color, float size) {
        player.spawnParticle(Particle.DUST, at.x() + x, at.y() + y, at.z() + z, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size));
    }
}
