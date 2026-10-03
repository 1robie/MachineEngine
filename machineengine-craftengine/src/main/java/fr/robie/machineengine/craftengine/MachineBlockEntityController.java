package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Glue between a CraftEngine block entity and a machine (§22.3).
 *
 * <ul>
 *   <li>{@code loadCustomData} may run while CraftEngine reads a chunk, possibly off the owner thread,
 *       so it only keeps the raw tag; decoding happens in {@code onLoad} on the owner thread.</li>
 *   <li>Without a running MachineEngine the raw tag is kept and written back unchanged (D3).</li>
 *   <li>CraftEngine only writes chunks flagged unsaved, so every machine change flags the chunk
 *       through {@link CEWorld#blockEntityChanged} (§20.2).</li>
 * </ul>
 */
public final class MachineBlockEntityController extends BlockEntityController {
    public static final String DATA_KEY = "machineengine:machine";

    private final MachineBlockBehavior behavior;
    private @Nullable CompoundTag pending;
    private @Nullable LoadedMachine machine;
    private @Nullable WorldPosition position;
    private @Nullable SleepingBlockEntityTicker<MachineBlockEntityController> ticker;
    private boolean warnedMissingType;
    private @Nullable MachineHopperContainer hopperContainer;
    private int comparatorSignal = -1;
    private final @Nullable MachineDisplayElement display;
    private final @Nullable LevelDisplayElement level;
    /** Per side, the part drawn while the side has a port ({@code side_display}). */
    private final java.util.Map<fr.robie.machineengine.api.world.RelativeSide, MachineDisplayElement> sides =
            new java.util.EnumMap<>(fr.robie.machineengine.api.world.RelativeSide.class);

    MachineBlockEntityController(BlockEntity blockEntity, MachineBlockBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
        this.display = behavior.displayItem() == null ? null : new MachineDisplayElement(behavior.displayItem(),
                behavior.displayRotation(), blockEntity.pos());
        this.level = behavior.levelDisplay() == null ? null
                : new LevelDisplayElement(behavior.levelDisplay().item(), behavior.levelDisplay().steps(), blockEntity.pos());
        if (behavior.sideDisplay() != null) {
            for (fr.robie.machineengine.api.world.RelativeSide side : fr.robie.machineengine.api.world.RelativeSide.values()) {
                MachineDisplayElement element = new MachineDisplayElement(behavior.sideDisplay() + "/" + side.name().toLowerCase(java.util.Locale.ROOT),
                        behavior.displayRotation(), blockEntity.pos());
                element.setVisible(false);
                this.sides.put(side, element);
            }
        }
    }

    @Override
    public boolean hasElement() {
        return this.display != null || this.level != null || !this.sides.isEmpty();
    }

    @Override
    public void gatherElements(java.util.function.Consumer<net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement> consumer) {
        if (this.display != null) consumer.accept(this.display);
        if (this.level != null) consumer.accept(this.level);
        this.sides.values().forEach(consumer);
    }

    /**
     * Shows each side's part while the side has a port, turned with the block. Cheap when nothing
     * changed: players get packets only for sides that appeared, vanished or turned.
     */
    void refreshSides() {
        MachineInstanceImpl instance = instance();
        if (this.sides.isEmpty() || instance == null) return;
        java.util.Set<fr.robie.machineengine.api.world.RelativeSide> used = java.util.EnumSet.noneOf(fr.robie.machineengine.api.world.RelativeSide.class);
        for (var port : instance.type().ports()) {
            if (instance.context().portDirection(port.id()) == fr.robie.machineengine.api.machine.PortDirection.DISABLED) continue;
            used.addAll(instance.context().portSides(port.id()));
        }
        java.util.List<MachineDisplayElement> changed = new java.util.ArrayList<>();
        for (var entry : this.sides.entrySet()) {
            boolean turned = entry.getValue().update(instance.facing(), false);
            boolean toggled = entry.getValue().setVisible(used.contains(entry.getKey()));
            if (turned || toggled) changed.add(entry.getValue());
        }
        if (changed.isEmpty()) return;
        CEWorld world = this.blockEntity.world();
        if (world == null) return;
        var chunk = world.getChunkAtIfLoaded(this.blockEntity.pos().x() >> 4, this.blockEntity.pos().z() >> 4);
        if (chunk == null) return;
        for (var player : chunk.getTrackedBy()) {
            if (!player.isDynamicBlockEntityVisible(this.blockEntity.pos())) continue;
            for (MachineDisplayElement element : changed) element.refresh(player);
        }
    }

    /** The gauge inside the block follows its storage; players see it only when the level changes. */
    private void refreshLevel(@Nullable MachineInstanceImpl instance) {
        if (this.level == null || instance == null) return;
        if (instance.type().storage(this.behavior.levelDisplay().storage()).isEmpty()) return;
        var views = instance.context().storage(this.behavior.levelDisplay().storage()).views();
        long amount = 0;
        long capacity = 0;
        for (var view : views) {
            amount += view.amount();
            capacity += view.capacity();
        }
        if (!this.level.update(amount, capacity)) return;
        CEWorld world = this.blockEntity.world();
        if (world == null) return;
        var chunk = world.getChunkAtIfLoaded(this.blockEntity.pos().x() >> 4, this.blockEntity.pos().z() >> 4);
        if (chunk == null) return;
        for (var player : chunk.getTrackedBy()) {
            if (player.isDynamicBlockEntityVisible(this.blockEntity.pos())) this.level.refresh(player);
        }
    }

    /** Turns the display entity to the facing and switches its model with the active state. */
    void refreshDisplay() {
        MachineInstanceImpl instance = instance();
        refreshLevel(instance);
        refreshSides();
        if (this.display == null) return;
        fr.robie.machineengine.api.world.Direction facing = instance != null ? instance.facing() : this.behavior.facing(this.blockEntity.blockState());
        if (!this.display.update(facing, instance != null && instance.isActive())) return;
        CEWorld world = this.blockEntity.world();
        if (world == null) return;
        var chunk = world.getChunkAtIfLoaded(this.blockEntity.pos().x() >> 4, this.blockEntity.pos().z() >> 4);
        if (chunk == null) return;
        for (var player : chunk.getTrackedBy()) {
            if (player.isDynamicBlockEntityVisible(this.blockEntity.pos())) this.display.refresh(player);
        }
    }

    public @Nullable LoadedMachine machine() {
        return this.machine;
    }

    @Nullable MachineInstanceImpl instance() {
        return this.machine == null ? null : this.machine.instance();
    }

    /** The vanilla container hoppers use; {@code null} while no live machine is attached. */
    @Nullable Object hopperContainer() {
        if (instance() == null) return null;
        if (this.hopperContainer == null) this.hopperContainer = new MachineHopperContainer(this);
        return this.hopperContainer.vanilla();
    }

    @Override
    public void loadCustomData(CompoundTag data) {
        Tag tag = data.get(DATA_KEY);
        if (tag instanceof CompoundTag compound) this.pending = compound.deepClone();
    }

    @Override
    public void saveCustomData(CompoundTag data) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (this.machine != null && host != null) {
            data.put(DATA_KEY, NbtData.toTag(this.machine.save(host.gameTime(this.machine.position().world()))));
        } else if (this.pending != null) {
            data.put(DATA_KEY, this.pending);
        }
    }

    @Override
    public void onLoad() {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null) return;
        WorldPosition at = host.position(this.blockEntity);
        this.position = at;
        // Registered first: creating or loading a machine already marks it dirty through the host.
        host.register(at, this);
        if (this.pending != null) {
            this.machine = host.engine().machines().load(NbtData.toData(this.pending), at, host);
            this.pending = null;
        } else {
            MachineType type = this.behavior.resolve(host.engine().registries());
            if (type == null) {
                if (!this.warnedMissingType) {
                    host.logger().warning("Block at " + at + " refers to machine type " + this.behavior.machineKey().asString()
                            + ", which is not registered; it stays inert");
                    this.warnedMissingType = true;
                }
                host.unregister(at, this);
                return;
            }
            this.machine = host.engine().machines().create(type, at, this.behavior.facing(this.blockEntity.blockState()), host);
        }
        syncTicker();
        refreshDisplay();
    }

    @Override
    public void onUnload() {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null || this.machine == null || this.position == null) return;
        host.engine().machines().unload(this.machine);
        host.unregister(this.position, this);
        host.machineGone(this.position);
    }

    /** Replaced by another machine block that takes the contents over (tier installers): no drops. */
    private boolean replacing;

    void replacing(boolean replacing) {
        this.replacing = replacing;
    }

    /** A replacement of another type takes over its predecessor's state (E6). */
    void adopt(fr.robie.machineengine.api.data.DataMap state) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null || this.machine == null || this.machine.instance() == null) return;
        this.machine = host.engine().machines().restoreAs(this.machine, state, host);
        syncTicker();
        refreshDisplay();
        contentsChanged();
    }

    @Override
    public void onRemove() {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null || this.machine == null || this.position == null) return;
        if (this.replacing) {
            host.engine().machines().unload(this.machine); // the new block's machine holds the state now
            host.unregister(this.position, this);
            this.machine = null;
            return;
        }
        java.util.Optional<fr.robie.machineengine.core.machine.CoreMachines.Packed> packed = this.behavior.keepsContents()
                ? host.engine().machines().pack(this.machine) : java.util.Optional.empty();
        if (packed.isPresent()) {
            host.keptContents().packed(this.position, packed.get(), this.behavior.block().id());
        } else {
            List<ResourceStack> drops = host.engine().machines().remove(this.machine);
            host.dropResources(this.position, drops);
        }
        host.unregister(this.position, this);
        host.machineGone(this.position);
        this.machine = null;
    }

    /**
     * The block was placed from an item (after every cancellable place event): restore the contents
     * it kept, if any (§20.5).
     */
    @Override
    public void loadCustomDataFromItem(net.momirealms.craftengine.core.item.Item item) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null || this.machine == null || this.machine.instance() == null) return;
        org.bukkit.inventory.ItemStack stack = net.momirealms.craftengine.bukkit.util.ItemStackUtils.getBukkitStack(item.minecraftItem());
        if (stack == null) return;
        fr.robie.machineengine.api.data.DataMap state = host.keptContents().read(stack);
        if (state == null) return;
        LoadedMachine restored = host.engine().machines().restore(this.machine, state, host);
        if (restored == this.machine) {
            host.logger().warning("Ignored machine contents for another machine type in the item placed at " + this.position);
            return;
        }
        this.machine = restored;
        syncTicker();
        refreshDisplay();
        contentsChanged();
    }

    @Override
    public void preBlockStateChange(ImmutableBlockState newState) {
        MachineInstanceImpl instance = instance();
        if (instance != null) instance.setFacing(this.behavior.facing(newState));
        refreshDisplay();
    }

    @Override
    public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world, ImmutableBlockState blockState) {
        this.ticker = new SleepingBlockEntityTicker<>((ceWorld, pos, state, controller) -> {
            MachineInstanceImpl instance = instance();
            if (instance != null) instance.tick();
        });
        syncTicker();
        return createTickerHelper(this.ticker);
    }

    /** Puts CraftEngine's ticker to sleep unless the machine is active (§17). */
    void syncTicker() {
        if (this.ticker == null) return;
        MachineInstanceImpl instance = instance();
        if (instance != null && instance.isValid() && instance.schedulerState() == SchedulerState.ACTIVE) {
            this.ticker.wakeUp();
        } else {
            this.ticker.sleep();
        }
    }

    /**
     * Contents changed: tells neighboring comparators, only when the signal actually changed (a
     * machine fed every tick must not spam block updates).
     */
    void contentsChanged() {
        MachineInstanceImpl instance = instance();
        refreshLevel(instance);
        if (!this.behavior.comparator() || instance == null) return;
        int signal = instance.comparatorSignal();
        if (signal == this.comparatorSignal) return;
        this.comparatorSignal = signal;
        CEWorld world = this.blockEntity.world();
        if (world == null) return;
        net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy.INSTANCE.updateNeighbourForOutputSignal(
                world.world().minecraftWorld(),
                net.momirealms.craftengine.bukkit.util.LocationUtils.toBlockPos(this.blockEntity.pos()),
                net.momirealms.craftengine.bukkit.util.BlockStateUtils.getBlockOwner(this.blockEntity.blockState().customBlockState().minecraftState()));
    }

    /** Admin purge of a placeholder: forget its data, so removing the block afterwards writes nothing back. */
    void purgePlaceholder() {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host != null && this.position != null) {
            host.unregister(this.position, this);
            host.machineGone(this.position);
        }
        this.machine = null;
        this.pending = null;
        markDirty();
    }

    /** Shows whether the machine works on the block ({@code active} property), keeping the block entity. */
    void activeChanged() {
        refreshDisplay();
        Property<Boolean> property = this.behavior.activeProperty();
        MachineInstanceImpl instance = instance();
        CEWorld world = this.blockEntity.world();
        if (property == null || instance == null || world == null) return;
        ImmutableBlockState state = world.getBlockStateAtIfLoaded(this.blockEntity.pos());
        if (state == null || !this.blockEntity.isValidBlockState(state)) return;
        // By name: after a CraftEngine reload, states carry new property objects.
        Property<Boolean> current = state.getProperty(property.name());
        if (current == null || state.get(current) == instance.isActive()) return;
        world.world().setBlockState(this.blockEntity.pos().x(), this.blockEntity.pos().y(), this.blockEntity.pos().z(),
                state.with(current, instance.isActive()), net.momirealms.craftengine.core.block.UpdateFlags.UPDATE_ALL);
    }

    /** Flags the chunk unsaved so CraftEngine writes the new machine state (§20.2). */
    void markDirty() {
        CEWorld world = this.blockEntity.world();
        if (world != null) world.blockEntityChanged(this.blockEntity.pos());
    }

    /**
     * Shutdown: snapshot the machine into a raw tag and detach it, so CraftEngine's final save does
     * not depend on MachineEngine any more.
     */
    void detachForShutdown(long gameTime) {
        if (this.machine == null) return;
        this.pending = NbtData.toTag(this.machine.save(gameTime));
        this.machine = null;
        markDirty();
    }
}
