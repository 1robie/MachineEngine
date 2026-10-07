package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.item.MachineTool;
import fr.robie.machineengine.api.paper.MachineInteractions;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.behavior.WorldlyContainerHolder;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

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
 *   keep_contents: true         # optional: the dropped item keeps the machine's contents (§20.5)
 *   hoppers: false              # optional: hoppers use the machine's item ports (default true, §22.4)
 *   comparator: false           # optional: comparators read how full the machine is (default true)
 *   active_property: active     # optional, defaults to "active" when the block has it: shown while working
 *   display_item: mymod:display/refinery   # optional: drawn by a display entity of this item (and <item>/active)
 *   display_rotation: 90        # optional: degrees the item is turned (clockwise from above) when facing north
 *   display_rotations: north=0/0,up=270/0   # optional: each facing's blockstate rotation x/y (wins over display_rotation)
 *   level_display: mymod:display/core   # optional: a gauge inside the block, items <prefix>/1..<steps> by how full
 *   level_storage: energy               #   the storage is (nothing when empty)
 *   level_steps: 4
 *   side_display: mymod:display/cube/port   # optional: <prefix>/<side> (front, back...) shown on each side
 *                                           #   that has a port (side configuration), turned like the block
 * }</pre>
 */
public final class MachineBlockBehavior extends BukkitBlockBehavior implements EntityBlock,
        WorldlyContainerHolder {
    public static final BlockBehaviorFactory<MachineBlockBehavior> FACTORY = new Factory();

    private final Key machine;
    private final @Nullable Property<net.momirealms.craftengine.core.util.Direction> facing;
    private final boolean keepContents;
    private final boolean hoppers;
    private final boolean comparator;
    private final @Nullable Property<Boolean> active;
    private final @Nullable String displayItem;
    private final int displayRotation;
    private final Map<fr.robie.machineengine.api.world.Direction, int[]> displayRotations;
    private final @Nullable LevelDisplay levelDisplay;
    private final @Nullable String sideDisplay;
    private int controllerId;

    /** A gauge drawn inside the block ({@code level_display}). */
    record LevelDisplay(String item, String storage, int steps) {
    }

    private MachineBlockBehavior(BlockDefinition block, Key machine,
                                 @Nullable Property<net.momirealms.craftengine.core.util.Direction> facing, boolean keepContents,
                                 boolean hoppers, boolean comparator, @Nullable Property<Boolean> active, @Nullable String displayItem,
                                 int displayRotation, @Nullable LevelDisplay levelDisplay, @Nullable String sideDisplay) {
        super(block);
        this.machine = machine;
        this.facing = facing;
        this.keepContents = keepContents;
        this.hoppers = hoppers;
        this.comparator = comparator;
        this.active = active;
        this.displayItem = displayItem;
        this.displayRotation = displayRotation;
        this.displayRotations = new EnumMap<>(fr.robie.machineengine.api.world.Direction.class);
        this.levelDisplay = levelDisplay;
        this.sideDisplay = sideDisplay;
    }

    @Nullable String sideDisplay() {
        return this.sideDisplay;
    }

    @Nullable LevelDisplay levelDisplay() {
        return this.levelDisplay;
    }

    /** The item a display entity shows instead of a block model (non-full models), or {@code null}. */
    @Nullable String displayItem() {
        return this.displayItem;
    }

    /** Degrees the display item is turned when facing north ({@code display_rotation}). */
    int displayRotation() {
        return this.displayRotation;
    }

    /** Each facing's blockstate rotation {x, y} in degrees ({@code display_rotations}); empty if not set. */
    Map<fr.robie.machineengine.api.world.Direction, int[]> displayRotations() {
        return this.displayRotations;
    }

    /** Reads "north=90/180,south=..." into {@link #displayRotations()}. */
    private MachineBlockBehavior withRotations(@Nullable String spec) {
        if (spec == null) return this;
        for (String entry : spec.split(",")) {
            String[] parts = entry.trim().split("[=/]");
            if (parts.length != 3) continue;
            try {
                this.displayRotations.put(fr.robie.machineengine.api.world.Direction.valueOf(parts[0].toUpperCase(Locale.ROOT)),
                        new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
            } catch (IllegalArgumentException ignored) {
                // a malformed entry: that facing falls back to display_rotation
            }
        }
        return this;
    }

    /** The boolean block property showing that the machine works, if the block has one. */
    @Nullable Property<Boolean> activeProperty() {
        return this.active;
    }

    boolean comparator() {
        return this.comparator;
    }

    @Override
    public boolean hasAnalogOutputSignal(Object thisBlock, Object[] args) {
        return this.comparator;
    }

    /** Args: state, level, pos. Read on the thread that owns the position. */
    @Override
    public int getAnalogOutputSignal(Object thisBlock, Object[] args) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (!this.comparator || host == null) return 0;
        World world = LevelProxy.INSTANCE.getWorld(args[1]);
        if (world == null) return 0;
        BlockPos pos = LocationUtils.fromBlockPos(args[2]);
        WorldPosition position = WorldPosition.of(world.getKey(), pos.x(), pos.y(), pos.z());
        if (!host.isOwnedByCurrentThread(position)) return 0;
        MachineInstanceImpl machine = host.machineAt(position);
        return machine == null ? 0 : machine.comparatorSignal();
    }

    /**
     * Hoppers, droppers and hopper minecarts see the machine's item ports as a container (§22.4).
     * Args: state, level, pos; returns a vanilla {@code WorldlyContainer} or {@code null}.
     */
    @Override
    public Object getContainer(Object thisBlock, Object[] args) {
        if (!this.hoppers) return null;
        World world = LevelProxy.INSTANCE.getWorld(args[1]);
        if (world == null) return null;
        BlockPos pos = LocationUtils.fromBlockPos(args[2]);
        BlockEntity blockEntity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(pos);
        if (blockEntity == null) return null;
        return blockEntity.controller.let(MachineBlockEntityController.class, this.controllerId, MachineBlockEntityController::hopperContainer);
    }

    /** Whether breaking the block keeps the machine's contents in the dropped item (§20.5). */
    public boolean keepsContents() {
        return this.keepContents;
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
        // By name: after a CraftEngine reload, states carry new property objects.
        Property<net.momirealms.craftengine.core.util.Direction> current = state.getProperty(this.facing.name());
        net.momirealms.craftengine.core.util.Direction value = current == null ? null : state.getNullable(current);
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
        World world = LevelProxy.INSTANCE.getWorld(args[1]);
        BlockPos pos = LocationUtils.fromBlockPos(args[2]);
        MachineInstanceImpl machine = host.machineAt(WorldPosition.of(world.getKey(), pos.x(), pos.y(), pos.z()));
        if (machine != null) machine.wake(WakeReason.NEIGHBOR_CHANGED);
    }

    /**
     * Right-click holding an item. CraftEngine asks the block before the held item, so the machine
     * offers the click to a machine tool first (D32); when the tool does nothing with it, the click
     * falls through to {@link #useWithoutItem} and opens the menu.
     */
    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        Item item = context.getItem();
        if (isMachineTool(item)) {
            InteractionResult result = item.getBehavior().orElseThrow().useOnBlock(context);
            if (result != InteractionResult.PASS) return result;
        }
        // Then what the machine type's block does on Paper (bins, personal storage...).
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host != null && context.getPlayer() != null && context.getHand() == InteractionHand.MAIN_HAND) {
            World world = (World) context.getLevel().platformWorld();
            BlockPos pos = context.getClickedPos();
            MachineInstanceImpl machine = host.machineAt(WorldPosition.of(world.getKey(), pos.x(), pos.y(), pos.z()));
            if (machine != null) {
                org.bukkit.entity.Player player = (org.bukkit.entity.Player) context.getPlayer().platformPlayer();
                MachineInteractions.Click click = new MachineInteractions.Click(player, player.getInventory().getItemInMainHand(),
                        machine, world.getBlockAt(pos.x(), pos.y(), pos.z()), BlockFace.valueOf(context.getClickedFace().name()),
                        context.isSecondaryUseActive());
                for (MachineInteractions.Interaction interaction : MachineInteractions.of(machine.inspect().type())) {
                    if (interaction.use(click)) return InteractionResult.SUCCESS_AND_CANCEL;
                }
            }
        }
        return InteractionResult.TRY_EMPTY_HAND;
    }

    /** An entity on the block: what the machine type's block does on Paper (teleporter pads...). */
    @Override
    public void stepOn(Object thisBlock, Object[] args) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        if (host == null) return;
        World world = LevelProxy.INSTANCE.getWorld(args[0]);
        BlockPos pos = LocationUtils.fromBlockPos(args[1]);
        MachineInstanceImpl machine = host.machineAt(WorldPosition.of(world.getKey(), pos.x(), pos.y(), pos.z()));
        if (machine == null) return;
        var interactions = MachineInteractions.of(machine.inspect().type());
        if (interactions.isEmpty()) return;
        Entity entity = EntityProxy.INSTANCE.getBukkitEntity(args[3]);
        for (MachineInteractions.Interaction interaction : interactions) interaction.steppedOn(machine, entity);
    }

    /** @return whether {@code item} has a behavior that acts on machines ({@link MachineTool}) */
    static boolean isMachineTool(@Nullable Item item) {
        if (item == null || item.isEmpty()) return false;
        boolean[] tool = {false};
        item.getBehavior().ifPresent(behavior -> behavior.let(MachineTool.class, candidate -> tool[0] |= candidate.actsOnMachines()));
        return tool[0];
    }

    /** Right-click: open the machine's menu (§23.3). */
    @Override
    public InteractionResult useWithoutItem(
            UseOnContext context, ImmutableBlockState state) {
        CraftEngineMachineHost host = CraftEngineMachineHost.current();
        net.momirealms.craftengine.core.entity.player.Player player = context.getPlayer();
        if (host == null || player == null || player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        World world = (World) context.getLevel().platformWorld();
        BlockPos pos = context.getClickedPos();
        boolean opened = host.interact((org.bukkit.entity.Player) player.platformPlayer(),
                WorldPosition.of(world.getKey(), pos.x(), pos.y(), pos.z()));
        return opened ? InteractionResult.SUCCESS_AND_CANCEL
                : InteractionResult.PASS;
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
            return new MachineBlockBehavior(block, machine, facing, section.getBoolean("keep_contents", false),
                    section.getBoolean("hoppers", true), section.getBoolean("comparator", true),
                    BlockBehaviorFactory.getOptionalProperty(block, section.getString("active_property", "active"), Boolean.class),
                    section.getString("display_item", (String) null),
                    section.getInt("display_rotation", 0),
                    section.getString("level_display", (String) null) == null ? null : new LevelDisplay(
                            section.getString("level_display", (String) null), section.getString("level_storage", "energy"),
                            section.getInt("level_steps", 4)),
                    section.getString("side_display", (String) null))
                    .withRotations(section.getString("display_rotations", (String) null));
        }
    }
}
