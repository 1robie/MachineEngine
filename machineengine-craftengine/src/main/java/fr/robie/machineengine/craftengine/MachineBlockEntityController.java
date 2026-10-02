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

    MachineBlockEntityController(BlockEntity blockEntity, MachineBlockBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
    }

    public @Nullable LoadedMachine machine() {
        return this.machine;
    }

    @Nullable MachineInstanceImpl instance() {
        return this.machine == null ? null : this.machine.instance();
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
    }

    @Override
    public void onUnload() {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null || this.machine == null || this.position == null) return;
        host.engine().machines().unload(this.machine);
        host.unregister(this.position, this);
    }

    @Override
    public void onRemove() {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null || this.machine == null || this.position == null) return;
        List<ResourceStack> drops = host.engine().machines().remove(this.machine);
        host.dropResources(this.position, drops);
        host.unregister(this.position, this);
        this.machine = null;
    }

    @Override
    public void preBlockStateChange(ImmutableBlockState newState) {
        MachineInstanceImpl instance = instance();
        if (instance != null) instance.setFacing(this.behavior.facing(newState));
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
