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
 * }</pre>
 */
public final class TransportBlockBehavior extends BukkitBlockBehavior implements EntityBlock {
    public static final BlockBehaviorFactory<TransportBlockBehavior> FACTORY =
            (block, section) -> new TransportBlockBehavior(block, Key.key(section.getNonEmptyString("transport")));

    private final Key transport;

    private TransportBlockBehavior(BlockDefinition block, Key transport) {
        super(block);
        this.transport = transport;
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
    }

    private static final class Controller extends BlockEntityController {
        private final TransportBlockBehavior behavior;
        private @Nullable WorldPosition position;

        private Controller(BlockEntity blockEntity, TransportBlockBehavior behavior) {
            super(blockEntity);
            this.behavior = behavior;
        }

        @Override
        public void onLoad() {
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host == null) return;
            TransportType type = this.behavior.resolve(host.engine().registries());
            if (type == null) return;
            this.position = host.position(this.blockEntity);
            host.engine().machines().networks().addTransport(type, this.position, host);
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
