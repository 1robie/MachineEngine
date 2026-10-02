package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.world.Direction;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The {@code machineengine:machine} CraftEngine block behavior (§22.2). Block configs reference a
 * machine type by key; the key is resolved lazily (D14) because CraftEngine parses its block configs
 * before MachineEngine's addons have registered.
 *
 * <pre>{@code
 * behavior:
 *   type: machineengine:machine
 *   machine: mymod:refinery
 *   facing_property: facing     # optional, defaults to "facing" when the block has it
 * }</pre>
 */
public final class MachineBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<MachineBlockBehavior> FACTORY = new Factory();

    private final Key machine;
    private final @Nullable Property<net.momirealms.craftengine.core.util.Direction> facing;
    private int controllerId;

    private MachineBlockBehavior(BlockDefinition block, Key machine,
                                 @Nullable Property<net.momirealms.craftengine.core.util.Direction> facing) {
        super(block);
        this.machine = machine;
        this.facing = facing;
    }

    /** The machine type key this block refers to. */
    public @NotNull Key machineKey() {
        return this.machine;
    }

    public @Nullable MachineType resolve(@NotNull RegistryAccess registries) {
        return registries.registry(Registries.MACHINE_TYPE).get(this.machine).orElse(null);
    }

    public @NotNull Direction facing(@NotNull ImmutableBlockState state) {
        if (this.facing == null) return Direction.NORTH;
        net.momirealms.craftengine.core.util.Direction value = state.getNullable(this.facing);
        return value == null ? Direction.NORTH : Direction.valueOf(value.name().toUpperCase(Locale.ROOT));
    }

    int controllerId() {
        return this.controllerId;
    }

    /** Wakes the machine so it re-checks its neighbors (§17.2). */
    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null) return;
        org.bukkit.World world = LevelProxy.INSTANCE.getWorld(args[1]);
        BlockPos pos = LocationUtils.fromBlockPos(args[2]);
        MachineInstanceImpl machine = host.machineAt(WorldPosition.of(world.getKey(), pos.x(), pos.y(), pos.z()));
        if (machine != null) machine.wake(WakeReason.NEIGHBOR_CHANGED);
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new MachineBlockEntityController(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    private static final class Factory implements BlockBehaviorFactory<MachineBlockBehavior> {
        @Override
        public MachineBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Key machine = Key.key(section.getNonEmptyString("machine"));
            String facingName = section.getString("facing_property", "facing");
            Property<net.momirealms.craftengine.core.util.Direction> facing =
                    BlockBehaviorFactory.getOptionalProperty(block, facingName, net.momirealms.craftengine.core.util.Direction.class);
            return new MachineBlockBehavior(block, machine, facing);
        }
    }
}
