package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.network.TransportType;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.world.WorldPosition;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * }</pre>
 *
 * With {@code connection_items}, the block shows its center piece and an arm toward each connected
 * side (same network, or a machine port carrying what the transport does), as display entities:
 * one block state, however the cable connects.
 */
public final class TransportBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<TransportBlockBehavior> FACTORY =
            (block, section) -> new TransportBlockBehavior(block, Key.key(section.getNonEmptyString("transport")),
                    section.getString("connection_items", (String) null));

    private final Key transport;
    private final @Nullable String connectionItems;
    private int controllerId;

    private TransportBlockBehavior(BlockDefinition block, Key transport, @Nullable String connectionItems) {
        super(block);
        this.transport = transport;
        this.connectionItems = connectionItems;
    }

    /** A neighbor changed: redraw this transport's connections (next task, once the neighbor is set up). */
    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (this.connectionItems == null) return;
        org.bukkit.World world = net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy.INSTANCE.getWorld(args[1]);
        if (world == null) return;
        net.momirealms.craftengine.core.world.BlockPos pos = net.momirealms.craftengine.bukkit.util.LocationUtils.fromBlockPos(args[2]);
        BlockEntity blockEntity = net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(world).storageWorld().getBlockEntityAtIfLoaded(pos);
        if (blockEntity == null) return;
        blockEntity.controller.let(Controller.class, this.controllerId, Controller::scheduleRefresh);
    }

    public @NotNull Key transportKey() {
        return this.transport;
    }

    public @Nullable TransportType resolve(@NotNull RegistryAccess registries) {
        return registries.registry(Registries.TRANSPORT_TYPE).get(this.transport).orElse(null);
    }

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new Controller(blockEntity, this);
    }

    @Override
    public void initControllerId(int id) {
        this.controllerId = id;
    }

    private static final class Controller extends BlockEntityController {
        private final TransportBlockBehavior behavior;
        private final @Nullable CableArmsElement arms;
        private @Nullable WorldPosition position;

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
        public void gatherElements(java.util.function.Consumer<net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement> consumer) {
            if (this.arms != null) consumer.accept(this.arms);
        }

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
                    .map(fr.robie.machineengine.api.resource.ResourceType::variant).toList();
            var connected = host.engine().machines().networks().connections(this.position, host, probes);
            if (connected.equals(this.arms.connected())) return;
            this.arms.setConnected(connected);
            var world = this.blockEntity.world();
            if (world == null) return;
            var chunk = world.getChunkAtIfLoaded(this.blockEntity.pos().x() >> 4, this.blockEntity.pos().z() >> 4);
            if (chunk == null) return;
            for (var player : chunk.getTrackedBy()) {
                if (player.isDynamicBlockEntityVisible(this.blockEntity.pos())) this.arms.refresh(player);
            }
        }

        @Override
        public void onLoad() {
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host == null) return;
            TransportType type = this.behavior.resolve(host.engine().registries());
            if (type == null) return;
            this.position = host.position(this.blockEntity);
            host.engine().machines().networks().addTransport(type, this.position, host);
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
            host.engine().machines().networks().removeTransport(this.position);
            this.position = null;
        }
    }
}
