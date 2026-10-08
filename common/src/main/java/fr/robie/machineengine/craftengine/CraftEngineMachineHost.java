package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.config.BlockOptions;
import fr.robie.machineengine.api.machine.MachineEnvironment;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.network.TransportSideMode;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.world.WorldFluid;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineHost;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.paper.MachineLocator;
import fr.robie.machineengine.paper.MachineMenuService;
import fr.robie.machineengine.paper.Messages;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.paper.PaperWorlds;
import fr.robie.machineengine.paper.event.PlayerMachineInteractEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
    private final Scheduler scheduler;
    private final CoreMachineEngine engine;
    private final Map<WorldPosition, MachineBlockEntityController> controllers = new ConcurrentHashMap<>();
    private final CraftEngineProtection protection;
    private volatile @Nullable MachineMenuService menus;
    private final KeptContents keptContents;

    private CraftEngineMachineHost(Plugin plugin, CoreMachineEngine engine) {
        this.plugin = plugin;
        this.scheduler = MachineEngine.scheduler(plugin);
        this.engine = engine;
        this.protection = new CraftEngineProtection(plugin.getLogger());
        this.keptContents = new KeptContents(plugin, this);
    }

    @Override
    public @NotNull BlockOptions options(@NotNull WorldPosition position) {
        MachineBlockEntityController controller = this.controllers.get(position);
        return controller == null ? BlockOptions.EMPTY : controller.currentBehavior().options();
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
            CraftEngineBlocks.remove(world.getBlockAt(
                    position.position().x(), position.position().y(), position.position().z()));
        }
        return "placeholder machine " + type + " deleted with its block";
    }

    /** Closes the menus of a machine that was removed or unloaded. */
    void machineGone(@NotNull WorldPosition position) {
        MachineMenuService service = this.menus;
        if (service != null) service.machineGone(position);
    }

    /** Keep-contents (§20.5); a listener the plugin registers. */
    public @NotNull KeptContents keptContents() {
        return this.keptContents;
    }

    /** Left-clicks on machine blocks for {@code MachineInteractions} (D32); a listener the plugin registers. */
    public @NotNull org.bukkit.event.Listener leftClicks() {
        return new MachineLeftClicks(this);
    }

    /** The menu integration (zMenu), or {@code null} for no machine GUIs. */
    public void setMenus(@Nullable MachineMenuService menus) {
        this.menus = menus;
    }

    /**
     * A player right-clicked a machine block (player's thread): checks permission and claims, then
     * opens the machine's menu.
     *
     * @return whether the click was handled
     */
    boolean interact(@NotNull Player player, @NotNull WorldPosition position) {
        MachineMenuService service = this.menus;
        LoadedMachine machine = machine(position);
        if (service == null || machine == null) return false;
        if (machine.instance() == null) {
            player.sendMessage(Messages.missingAddon(machine.inspect().type().asString()));
            return true;
        }
        if (!player.hasPermission("machineengine.use")) return false;
        World world = world(position.world());
        if (world != null && !this.protection.canOpen(player, new Location(world,
                position.position().x(), position.position().y(), position.position().z()))) {
            return true;
        }
        var event = new PlayerMachineInteractEvent(player, machine.instance());
        if (!event.callEvent()) return true;
        return service.open(player, machine);
    }

    public static @NotNull CraftEngineMachineHost start(@NotNull Plugin plugin, @NotNull CoreMachineEngine engine) {
        CraftEngineMachineHost host = new CraftEngineMachineHost(plugin, engine);
        current = host;
        MachineLocator.setCurrent(host);
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
        MachineLocator.setCurrent(null);
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
                .filter(Objects::nonNull).toList();
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
        return world != null && this.scheduler.isOwnedByCurrentThread(world, position.position().chunkX(), position.position().chunkZ());
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
        this.scheduler.runLater(world, position.position().chunkX(), position.position().chunkZ(),
                () -> machine.timerFired(world.getGameTime()), delay);
    }

    @Override
    public void markDirty(@NotNull MachineInstanceImpl machine) {
        withController(machine.position(), MachineBlockEntityController::markDirty);
    }

    @Override
    public void activityChanged(@NotNull MachineInstanceImpl machine) {
        withController(machine.position(), MachineBlockEntityController::syncTicker);
    }

    @Override
    public @NotNull MachineEnvironment environment(@NotNull WorldPosition position) {
        World world = world(position.world());
        if (world == null) return MachineEnvironment.NONE;
        Block above = world.getBlockAt(position.position().x(), position.position().y() + 1, position.position().z());
        int skyLight = above.getLightFromSky();
        boolean skyVisible = world.getHighestBlockYAt(above.getX(), above.getZ()) < above.getY() || skyLight >= 15;
        boolean daytime = world.isDayTime() && world.getEnvironment() == World.Environment.NORMAL;
        boolean raining = world.hasStorm() && above.getTemperature() < 2.0;
        return new MachineEnvironment(skyVisible, daytime, raining, skyLight);
    }

    @Override
    public @Nullable WorldFluid worldFluidAt(@NotNull WorldPosition position) {
        Block block = ownedBlock(position);
        if (block == null || !(block.getBlockData() instanceof Levelled levelled)) return null;
        Key fluid = fluidKey(block.getType());
        return fluid == null ? null : new WorldFluid(fluid, levelled.getLevel() == 0);
    }

    @Override
    public boolean takeWorldFluid(@NotNull WorldPosition position) {
        var fluid = worldFluidAt(position);
        if (fluid == null || !fluid.source()) return false;
        Block block = ownedBlock(position);
        if (block == null) return false;
        block.setType(Material.AIR, true);
        return true;
    }

    /**
     * CraftEngine reloaded its configs: every loaded machine and transport reads its block config again
     * (D34), each on the thread owning it.
     */
    void blockConfigsReloaded() {
        this.controllers.forEach((position, controller) -> post(position, controller::optionsReloaded));
        this.transports.forEach((position, controller) -> post(position, () -> controller.typeReloaded(this)));
        logger().info("Block configs reloaded: " + this.controllers.size() + " loaded machines and " + this.transports.size()
                + " transports read them again");
    }

    /** Loaded transport blocks, for their side modes. */
    private final Map<WorldPosition, TransportBlockBehavior.Controller> transports = new ConcurrentHashMap<>();

    void registerTransport(WorldPosition position, TransportBlockBehavior.Controller controller) {
        this.transports.put(position, controller);
    }

    void unregisterTransport(WorldPosition position, TransportBlockBehavior.Controller controller) {
        this.transports.remove(position, controller);
    }

    @Override
    public @Nullable TransportSideMode cycleTransportSide(@NotNull WorldPosition position,
                                                                                          @NotNull fr.robie.machineengine.api.world.Direction side) {
        TransportBlockBehavior.Controller controller = this.transports.get(position);
        if (controller == null || !isOwnedByCurrentThread(position)) return null;
        var networks = this.engine.machines().networks();
        var mode = networks.sideMode(position, side).next();
        networks.setSideMode(position, side, mode);
        controller.markDirty();
        controller.scheduleRefresh();
        // A transport on that side redraws too: its arm toward this one follows the link.
        TransportBlockBehavior.Controller neighbor = this.transports.get(position.relative(side));
        if (neighbor != null) neighbor.scheduleRefresh();
        return mode;
    }

    @Override
    public @Nullable ResourceHandler worldHandlerAt(@NotNull WorldPosition position,
                                                                                       @NotNull fr.robie.machineengine.api.world.Direction side) {
        if (this.controllers.containsKey(position)) return null; // a machine block, handled as a machine
        Block block = ownedBlock(position);
        return block == null ? null : ContainerHandler.of(block.getState(false));
    }

    @Override
    public boolean replaceBlock(@NotNull WorldPosition position, @NotNull Key blockId,
                                @Nullable fr.robie.machineengine.api.world.Direction facingOverride) {
        MachineBlockEntityController controller = this.controllers.get(position);
        if (controller == null || controller.machine() == null || controller.machine().instance() == null
                || !isOwnedByCurrentThread(position)) return false;
        MachineInstanceImpl old = controller.machine().instance();
        // The process belongs to the old type's behavior: give its inputs back first.
        if (old.context().process().current() != null) old.context().process().cancel();
        if (old.context().process().current() != null) return false;
        var packed = this.engine.machines().pack(controller.machine());
        if (packed.isEmpty() && old.holdsContents()) return false; // a transfer in flight
        World world = world(position.world());
        var definition = CraftEngineBlocks.byId(
                net.momirealms.craftengine.core.util.Key.of(blockId.namespace(), blockId.value()));
        if (world == null || definition == null) return false;
        // Same facing as the old block, if the new one has a facing property.
        ImmutableBlockState state = definition.defaultState();
        var facing = definition.getProperty("facing");
        if (facing != null) {
            try {
                state = ImmutableBlockState.with(state, facing,
                        net.momirealms.craftengine.core.util.Direction.valueOf((facingOverride != null ? facingOverride : old.facing()).name()));
            } catch (RuntimeException e) {
                // a 4-direction block and an up/down facing: keep the default
            }
        }
        controller.replacing(true); // its removal must not drop what the new machine takes over
        var at = position.position();
        boolean placed = CraftEngineBlocks.place(
                new Location(world, at.x(), at.y(), at.z()), state, false);
        if (!placed) {
            controller.replacing(false);
            return false;
        }
        MachineBlockEntityController fresh = this.controllers.get(position);
        if (fresh != null && packed.isPresent()) {
            fresh.adopt(packed.get().state());
        } else if (packed.isPresent()) {
            dropResources(position, packed.get().contents()); // not a machine block after all
        }
        return true;
    }

    @Override
    public @Nullable Key blockAt(@NotNull WorldPosition position) {
        Block block = ownedBlock(position);
        if (block == null) return null;
        var custom = CraftEngineBlocks.getCustomBlockState(block);
        if (custom != null && !custom.isEmpty()) {
            var id = custom.owner().value().id();
            return Key.key(id.namespace(), id.value());
        }
        return block.getType().getKey();
    }

    @Override
    public boolean placeWorldFluid(@NotNull WorldPosition position, @NotNull Key fluid) {
        Block block = ownedBlock(position);
        if (block == null || this.controllers.containsKey(position)) return false;
        Material material = fluidMaterial(fluid);
        if (material == null) return false;
        boolean air = block.getType().isAir();
        boolean flowing = block.getType() == material && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() != 0;
        if (!air && !flowing) return false;
        block.setType(material, true);
        return true;
    }

    @Override
    public boolean placeWorldBlock(@NotNull WorldPosition position, @NotNull Key block) {
        Block at = ownedBlock(position);
        if (at == null || this.controllers.containsKey(position)) return false;
        Material material = Material.matchMaterial(block.asString());
        if (material == null || !material.isBlock()) return false;
        if (!at.getType().isAir() && !at.isLiquid()) return false;
        at.setType(material, true);
        return true;
    }

    @Override
    public float worldBlockHardness(@NotNull WorldPosition position) {
        Block block = ownedBlock(position);
        if (block == null || block.getType().isAir()) return -1;
        return block.getType().getHardness();
    }

    @Override
    public boolean breakWorldBlock(@NotNull WorldPosition position) {
        Block block = ownedBlock(position);
        if (block == null || block.getType().isAir() || block.getType().getHardness() < 0) return false;
        // Machines and transports are never broken by other machines (as in the test world).
        if (this.controllers.containsKey(position) || this.transports.containsKey(position)) return false;
        if (CraftEngineBlocks.isCustomBlock(block)) {
            return CraftEngineBlocks.remove(block, null, false, true, true);
        }
        return block.breakNaturally();
    }

    @Override
    public @NotNull List<ResourceStack> collectWorldItems(@NotNull WorldPosition position) {
        World world = world(position.world());
        if (world == null || !isOwnedByCurrentThread(position)) return List.of();
        var at = position.position();
        List<ResourceStack> taken = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(new BoundingBox(at.x(), at.y(), at.z(), at.x() + 1, at.y() + 1, at.z() + 1))) {
            if (!(entity instanceof Item item) || item.isDead()) continue;
            taken.add(PaperItems.toResource(item.getItemStack()));
            item.remove();
        }
        return taken;
    }

    @Override
    public int redstonePower(@NotNull WorldPosition position) {
        Block block = ownedBlock(position);
        return block == null ? 0 : block.getBlockPower();
    }

    @Override
    public void playSound(@NotNull WorldPosition position, @NotNull Key sound, float volume, float pitch) {
        World world = world(position.world());
        if (world == null) return;
        var at = position.position();
        world.playSound(Sound.sound(sound, Sound.Source.BLOCK, volume, pitch),
                at.x() + 0.5, at.y() + 0.5, at.z() + 0.5);
    }

    @Override
    public void particleLine(@NotNull WorldPosition from, @NotNull WorldPosition to, int color) {
        World world = world(from.world());
        if (world == null) return;
        var a = from.position();
        var b = to.position();
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        int steps = (int) Math.max(1, Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 2));
        var dust = new Particle.DustOptions(Color.fromRGB(color & 0xFFFFFF), 1.0f);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            world.spawnParticle(Particle.DUST, a.x() + 0.5 + dx * t, a.y() + 0.5 + dy * t, a.z() + 0.5 + dz * t, 1, dust);
        }
    }

    /** The block at {@code position} if its chunk is loaded and this thread owns it, else null. */
    private @Nullable Block ownedBlock(WorldPosition position) {
        World world = world(position.world());
        if (world == null || !isOwnedByCurrentThread(position)) return null;
        var at = position.position();
        if (!world.isChunkLoaded(at.chunkX(), at.chunkZ())) return null;
        return world.getBlockAt(at.x(), at.y(), at.z());
    }

    @Override
    public void portsChanged(@NotNull MachineInstanceImpl machine) {
        withController(machine.position(), MachineBlockEntityController::refreshSides);
    }

    @Override
    public void setBlockProperty(@NotNull WorldPosition position, @NotNull String property, @NotNull String value) {
        if (isOwnedByCurrentThread(position)) withController(position, controller -> controller.setProperty(property, value));
    }

    @Override
    public void activeChanged(@NotNull MachineInstanceImpl machine) {
        withController(machine.position(), MachineBlockEntityController::activeChanged);
    }

    @Override
    public void contentsChanged(@NotNull MachineInstanceImpl machine) {
        withController(machine.position(), MachineBlockEntityController::contentsChanged);
        MachineMenuService service = this.menus;
        if (service != null) service.machineChanged(machine.position());
    }

    @Override
    public void post(@NotNull WorldPosition target, @NotNull Runnable task) {
        World world = world(target.world());
        if (world == null) return;
        this.scheduler.run(world, target.position().chunkX(), target.position().chunkZ(), task);
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
        return PaperWorlds.world(key);
    }

    /** Runs {@code action} on the controller of the machine at {@code position}, if there is one. */
    private void withController(WorldPosition position, Consumer<MachineBlockEntityController> action) {
        MachineBlockEntityController controller = this.controllers.get(position);
        if (controller != null) action.accept(controller);
    }

    private static final Key WATER = Key.key("minecraft", "water");
    private static final Key LAVA = Key.key("minecraft", "lava");

    /** The block material of a fluid's source block, or null for a fluid the platform has no block for. */
    private static @Nullable Material fluidMaterial(Key fluid) {
        return fluid.equals(WATER) ? Material.WATER : fluid.equals(LAVA) ? Material.LAVA : null;
    }

    private static @Nullable Key fluidKey(Material material) {
        return switch (material) {
            case WATER -> WATER;
            case LAVA -> LAVA;
            default -> null;
        };
    }
}
