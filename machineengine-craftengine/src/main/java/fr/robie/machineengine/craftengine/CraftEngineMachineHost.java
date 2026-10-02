package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineHost;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.paper.MachineLocator;
import fr.robie.machineengine.paper.PaperItems;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts machines in CraftEngine block entities on Paper (§22, §23). Timers and cross-region
 * messages use Paper's region scheduler, which runs on the main thread on Paper and on the owning
 * region on Folia.
 *
 * <p>The current host is held statically because CraftEngine creates controllers from block configs
 * parsed before MachineEngine enables; this is internal to the adapter.</p>
 */
public final class CraftEngineMachineHost implements MachineHost, MachineLocator {
    private static volatile @Nullable CraftEngineMachineHost current;

    private final Plugin plugin;
    private final CoreMachineEngine engine;
    private final Map<WorldPosition, MachineBlockEntityController> controllers = new ConcurrentHashMap<>();
    private final CraftEngineProtection protection;
    private volatile @Nullable fr.robie.machineengine.paper.MachineMenuService menus;
    private final KeptContents keptContents;

    private CraftEngineMachineHost(Plugin plugin, CoreMachineEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.protection = new CraftEngineProtection(plugin.getLogger());
        this.keptContents = new KeptContents(plugin, this);
    }

    @Override
    public @Nullable String purge(@NotNull WorldPosition position) {
        MachineBlockEntityController controller = this.controllers.get(position);
        LoadedMachine machine = controller == null ? null : controller.machine();
        if (machine == null) return null;
        MachineInstanceImpl instance = machine.instance();
        if (instance != null) {
            int purged = instance.purgeUnknown();
            return purged == 0 ? null : purged + " unknown entr" + (purged == 1 ? "y" : "ies") + " deleted from " + instance.type().key().asString();
        }
        String type = machine.inspect().type().asString();
        controller.purgePlaceholder();
        World world = world(position.world());
        if (world != null) {
            net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.remove(world.getBlockAt(
                    position.position().x(), position.position().y(), position.position().z()));
        }
        return "placeholder machine " + type + " deleted with its block";
    }

    /** Closes the menus of a machine that was removed or unloaded. */
    void machineGone(@NotNull WorldPosition position) {
        fr.robie.machineengine.paper.MachineMenuService service = this.menus;
        if (service != null) service.machineGone(position);
    }

    /** Keep-contents (§20.5); a listener the plugin registers. */
    public @NotNull KeptContents keptContents() {
        return this.keptContents;
    }

    /** The menu integration (zMenu), or {@code null} for no machine GUIs. */
    public void setMenus(@Nullable fr.robie.machineengine.paper.MachineMenuService menus) {
        this.menus = menus;
    }

    /**
     * A player right-clicked a machine block (player's thread): checks permission and claims, then
     * opens the machine's menu.
     *
     * @return whether the click was handled
     */
    boolean interact(@NotNull org.bukkit.entity.Player player, @NotNull WorldPosition position) {
        fr.robie.machineengine.paper.MachineMenuService service = this.menus;
        LoadedMachine machine = machine(position);
        if (service == null || machine == null) return false;
        if (machine.instance() == null) {
            player.sendMessage(fr.robie.machineengine.paper.Messages.missingAddon(machine.inspect().type().asString()));
            return true;
        }
        if (!player.hasPermission("machineengine.use")) return false;
        World world = world(position.world());
        if (world != null && !this.protection.canOpen(player, new org.bukkit.Location(world,
                position.position().x(), position.position().y(), position.position().z()))) {
            return true;
        }
        var event = new fr.robie.machineengine.paper.event.PlayerMachineInteractEvent(player, machine.instance());
        if (!event.callEvent()) return true;
        return service.open(player, machine);
    }

    public static @NotNull CraftEngineMachineHost start(@NotNull Plugin plugin, @NotNull CoreMachineEngine engine) {
        CraftEngineMachineHost host = new CraftEngineMachineHost(plugin, engine);
        current = host;
        return host;
    }

    static @Nullable CraftEngineMachineHost current() {
        return current;
    }

    /**
     * Snapshots every loaded machine into its block entity and detaches it, so CraftEngine's final
     * world save keeps the data without calling back into MachineEngine (§8.2 shutdown).
     */
    public void shutdown() {
        for (MachineBlockEntityController controller : this.controllers.values()) {
            LoadedMachine machine = controller.machine();
            if (machine == null) continue;
            try {
                controller.detachForShutdown(gameTime(machine.position().world()));
            } catch (RuntimeException e) {
                logger().log(Level.SEVERE, "Failed to save the machine at " + machine.position(), e);
            }
        }
        this.controllers.clear();
        current = null;
    }

    public @NotNull CoreMachineEngine engine() {
        return this.engine;
    }

    @NotNull Logger logger() {
        return this.plugin.getLogger();
    }

    WorldPosition position(BlockEntity blockEntity) {
        World world = (World) blockEntity.world().world().platformWorld();
        return WorldPosition.of(world.getKey(), blockEntity.pos().x(), blockEntity.pos().y(), blockEntity.pos().z());
    }

    void register(WorldPosition position, MachineBlockEntityController controller) {
        this.controllers.put(position, controller);
    }

    void unregister(WorldPosition position, MachineBlockEntityController controller) {
        this.controllers.remove(position, controller);
    }

    /** Loaded machines (including placeholders) in one chunk. */
    public int machinesInChunk(@NotNull Key world, int chunkX, int chunkZ) {
        int count = 0;
        for (WorldPosition position : this.controllers.keySet()) {
            if (position.world().equals(world) && position.position().chunkX() == chunkX && position.position().chunkZ() == chunkZ) count++;
        }
        return count;
    }

    // -- MachineLocator ------------------------------------------------------------------------

    @Override
    public @Nullable LoadedMachine machine(@NotNull WorldPosition position) {
        MachineBlockEntityController controller = this.controllers.get(position);
        return controller == null ? null : controller.machine();
    }

    @Override
    public @NotNull Collection<LoadedMachine> loadedMachines() {
        return this.controllers.values().stream().map(MachineBlockEntityController::machine)
                .filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public void wakeAll(@NotNull WakeReason reason) {
        for (MachineBlockEntityController controller : this.controllers.values()) {
            MachineInstanceImpl instance = controller.instance();
            if (instance != null) post(instance.position(), () -> instance.wake(reason));
        }
    }

    // -- MachineHost ---------------------------------------------------------------------------

    @Override
    public long gameTime(@NotNull Key world) {
        World bukkit = world(world);
        return bukkit == null ? 0 : bukkit.getGameTime();
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull WorldPosition position) {
        World world = world(position.world());
        return world != null && Bukkit.isOwnedByCurrentRegion(world, position.position().chunkX(), position.position().chunkZ());
    }

    @Override
    public @Nullable MachineInstanceImpl machineAt(@NotNull WorldPosition position) {
        MachineBlockEntityController controller = this.controllers.get(position);
        return controller == null ? null : controller.instance();
    }

    @Override
    public void scheduleWake(@NotNull MachineInstanceImpl machine, long gameTime) {
        WorldPosition position = machine.position();
        World world = world(position.world());
        if (world == null) return;
        long delay = Math.max(1, gameTime - world.getGameTime());
        Bukkit.getRegionScheduler().runDelayed(this.plugin, world, position.position().chunkX(), position.position().chunkZ(),
                task -> machine.timerFired(world.getGameTime()), delay);
    }

    @Override
    public void markDirty(@NotNull MachineInstanceImpl machine) {
        MachineBlockEntityController controller = this.controllers.get(machine.position());
        if (controller != null) controller.markDirty();
    }

    @Override
    public void activityChanged(@NotNull MachineInstanceImpl machine) {
        MachineBlockEntityController controller = this.controllers.get(machine.position());
        if (controller != null) controller.syncTicker();
    }

    @Override
    public void contentsChanged(@NotNull MachineInstanceImpl machine) {
        MachineBlockEntityController controller = this.controllers.get(machine.position());
        if (controller != null) controller.contentsChanged();
    }

    @Override
    public void post(@NotNull WorldPosition target, @NotNull Runnable task) {
        World world = world(target.world());
        if (world == null) return;
        Bukkit.getRegionScheduler().execute(this.plugin, world, target.position().chunkX(), target.position().chunkZ(), task);
    }

    @Override
    public void dropResources(@NotNull WorldPosition position, @NotNull List<ResourceStack> resources) {
        if (resources.isEmpty()) return;
        World world = world(position.world());
        if (world == null) return;
        List<ResourceStack> voided = PaperItems.drop(world, position.position().x(), position.position().y(),
                position.position().z(), resources);
        // Only items have a world form; other resources (energy, fluids …) are lost with the block.
        if (!voided.isEmpty()) logger().fine("Voided " + voided + " at " + position + ": no world form for these resources");
    }

    private static @Nullable World world(Key key) {
        return Bukkit.getWorld(new NamespacedKey(key.namespace(), key.value()));
    }
}
