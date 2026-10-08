package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.network.TransportSideMode;
import fr.robie.machineengine.api.network.TransportType;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@code machineengine:transport} CraftEngine block behavior (§15): a cable or pipe of a
 * registered {@link TransportType}. Transports keep no data; networks are rebuilt from the loaded
 * transports, so the block entity only reports load, unload and removal.
 *
 * <pre>{@code
 * behavior:
 *   type: machineengine:transport
 *   transport: mymod:copper_cable
 *   connection_items: mymod:cable_part/copper   # optional: arm models <prefix>/<direction>
 *   rate: 8000                                  # optional: per tick, replacing the transport type's (D34)
 * }</pre>
 *
 * With {@code connection_items}, the block shows its center piece and an arm toward each connected
 * side (same network, or a machine port carrying what the transport does), as display entities:
 * one block state, however the cable connects.
 */
public final class TransportBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<TransportBlockBehavior> FACTORY =
            (block, section) -> new TransportBlockBehavior(block, Key.key(section.getNonEmptyString("transport")),
                    section.getString("connection_items", (String) null), rate(block, section));

    private final Key transport;
    private final @Nullable String connectionItems;
    /** The block config's {@code rate}, or 0 for the transport type's. */
    private final long rate;
    /** The registered type and this block's copy of it at {@link #rate}, made once per registered type. */
    private volatile @Nullable TransportType[] resolved;
    private int controllerId;

    private TransportBlockBehavior(BlockDefinition block, Key transport, @Nullable String connectionItems, long rate) {
        super(block);
        this.transport = transport;
        this.connectionItems = connectionItems;
        this.rate = rate;
    }

    private static long rate(BlockDefinition block, ConfigSection section) {
        if (!section.keySet().contains("rate")) return 0;
        long rate = section.getLong("rate", 0L);
        if (rate < 1) {
            BlockConfigOptions.warn(block.id() + ": rate must be a whole number from 1 but is " + rate + "; using the transport's own");
            return 0;
        }
        return rate;
    }

    /** A neighbor changed: redraw this transport's connections (next task, once the neighbor is set up). */
    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (this.connectionItems == null) return;
        World world = LevelProxy.INSTANCE.getWorld(args[1]);
        if (world == null) return;
        BlockPos pos = LocationUtils.fromBlockPos(args[2]);
        BlockEntity blockEntity = BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(pos);
        if (blockEntity == null) return;
        blockEntity.controller.let(Controller.class, this.controllerId, Controller::scheduleRefresh);
    }

    public @NotNull Key transportKey() {
        return this.transport;
    }

    public @Nullable TransportType resolve(@NotNull RegistryAccess registries) {
        TransportType type = registries.registry(Registries.TRANSPORT_TYPE).get(this.transport).orElse(null);
        if (type == null || this.rate == 0) return type;
        TransportType[] resolved = this.resolved;
        if (resolved == null || resolved[0] != type) {
            resolved = new TransportType[]{type, type.withRate(this.rate)};
            this.resolved = resolved;
        }
        return resolved[1];
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new Controller(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    static final class Controller extends BlockEntityController {
        /** Side modes (pull, push, none) saved with the block. */
        private static final String SIDES_KEY = "machineengine:sides";
        private final TransportBlockBehavior behavior;
        private final @Nullable CableArmsElement arms;
        private @Nullable WorldPosition position;
        /** Side modes read from the block, applied when it joins its network, and kept for saving after it left. */
        private Map<Direction, TransportSideMode> modes =
                Map.of();

        private Controller(BlockEntity blockEntity, TransportBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
            this.arms = behavior.connectionItems == null ? null : new CableArmsElement(behavior.connectionItems, blockEntity.pos());
        }

        @Override
        public boolean hasElement() {
            return this.arms != null;
        }

        @Override
        public void gatherElements(Consumer<BlockEntityElement> consumer) {
            if (this.arms != null) consumer.accept(this.arms);
        }

        /** Redraws the arms (e.g. after a side mode changed). */
        void scheduleRefresh() {
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host == null || this.position == null || this.arms == null) return;
            host.post(this.position, this::refresh);
        }

        /** Recomputes the connected sides and redraws them for the players who see the block. */
        private void refresh() {
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host == null || this.position == null || this.arms == null) return;
            var probes = host.engine().registries().registry(Registries.RESOURCE_TYPE).values().stream()
                    .map(ResourceType::variant).toList();
            var networks = host.engine().machines().networks();
            // A neighbor block changed (e.g. a chest placed): the network re-reads its endpoints.
            for (Direction direction : Direction.values()) {
                networks.machineChanged(this.position.relative(direction));
            }
            var connected = networks.connections(this.position, host, probes);
            // Pull and push sides toward blocks (not other transports) get Mekanism's funnel and nozzle arms.
            var kinds = new EnumMap<Direction, String>(Direction.class);
            for (var entry : networks.sideModes(this.position).entrySet()) {
                if (!connected.contains(entry.getKey()) || networks.transportAt(this.position.relative(entry.getKey())) != null) continue;
                if (entry.getValue() == TransportSideMode.PULL) kinds.put(entry.getKey(), "_pull");
                if (entry.getValue() == TransportSideMode.PUSH) kinds.put(entry.getKey(), "_push");
            }
            if (connected.equals(this.arms.connected()) && kinds.equals(this.arms.kinds())) return;
            this.arms.setConnected(connected);
            this.arms.setKinds(kinds);
            var world = this.blockEntity.world();
            if (world == null) return;
            var chunk = world.getChunkAtIfLoaded(this.blockEntity.pos().x() >> 4, this.blockEntity.pos().z() >> 4);
            if (chunk == null) return;
            for (var player : chunk.getTrackedBy()) {
                if (player.isDynamicBlockEntityVisible(this.blockEntity.pos())) this.arms.refresh(player);
            }
        }

        @Override
        public void loadCustomData(CompoundTag data) {
            if (!(data.get(SIDES_KEY) instanceof CompoundTag sides)) return;
            var read = new EnumMap<Direction, TransportSideMode>(
                    Direction.class);
            NbtData.toData(sides).entries().forEach((side, value) -> {
                try {
                    read.put(Direction.valueOf(side.toUpperCase(Locale.ROOT)),
                            TransportSideMode.valueOf(
                                    ((DataValue.DataString) value).value().toUpperCase(Locale.ROOT)));
                } catch (RuntimeException ignored) {
                    // an unknown side or mode (newer version): left at the default
                }
            });
            this.modes = Map.copyOf(read);
        }

        @Override
        public void saveCustomData(CompoundTag data) {
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host != null && this.position != null) this.modes = host.engine().machines().networks().sideModes(this.position);
            if (this.modes.isEmpty()) return;
            DataMap.Builder sides = DataMap.builder();
            this.modes.forEach((side, mode) -> sides.put(side.name().toLowerCase(Locale.ROOT), mode.name().toLowerCase(Locale.ROOT)));
            data.put(SIDES_KEY, NbtData.toTag(sides.build()));
        }

        /** The block's data changed (a side mode): CraftEngine saves it with the chunk. */
        void markDirty() {
            var world = this.blockEntity.world();
            if (world != null) world.blockEntityChanged(this.blockEntity.pos());
        }

        @Override
        public void onLoad() {
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host == null) return;
            TransportType type = this.behavior.resolve(host.engine().registries());
            if (type == null) return;
            this.position = host.position(this.blockEntity);
            var networks = host.engine().machines().networks();
            networks.addTransport(type, this.position, host);
            for (var entry : this.modes.entrySet()) networks.setSideMode(this.position, entry.getKey(), entry.getValue());
            host.registerTransport(this.position, this);
            scheduleRefresh();
        }

        @Override
        public void onUnload() {
            leave();
        }

        @Override
        public void onRemove() {
            leave();
        }

        private void leave() {
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host == null || this.position == null) return;
            this.modes = host.engine().machines().networks().sideModes(this.position);
            host.unregisterTransport(this.position, this);
            host.engine().machines().networks().removeTransport(this.position);
            this.position = null;
        }
    }
}
