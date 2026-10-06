package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.registry.RegistryAccess;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Registration of MachineEngine's CraftEngine block behavior and checks of block configs (§22.2). */
public final class CraftEngineIntegration {

    private CraftEngineIntegration() {
    }

    /** Must run in MachineEngine's {@code onLoad}, while CraftEngine's behavior registry is open. */
    public static void registerBlockBehavior() {
        BlockBehaviors.register(CraftEngineKeys.toCraftEngine(MachineEngineKeys.key("machine")), MachineBlockBehavior.FACTORY);
        BlockBehaviors.register(CraftEngineKeys.toCraftEngine(MachineEngineKeys.key("transport")), TransportBlockBehavior.FACTORY);
    }

    /** @return whether {@code state} is a block with the {@code machineengine:machine} behavior */
    public static boolean isMachineBlock(@NotNull ImmutableBlockState state) {
        return state.behavior().getFirst(MachineBlockBehavior.class) != null;
    }

    /**
     * @return one message per CraftEngine block whose {@code machine:} key is not a registered
     *         machine type (D14); such blocks stay inert
     */
    public static @NotNull List<String> unboundBlocks(@NotNull RegistryAccess registries) {
        List<String> problems = new ArrayList<>();
        for (BlockDefinition block : CraftEngineBlocks.loadedBlocks().values()) {
            MachineBlockBehavior behavior = block.defaultState().behavior().getFirst(MachineBlockBehavior.class);
            if (behavior != null && behavior.resolve(registries) == null) {
                problems.add("CraftEngine block " + block.id().asString() + " refers to machine type "
                        + behavior.machineKey().asString() + ", which is not registered");
            }
            TransportBlockBehavior transport = block.defaultState().behavior().getFirst(TransportBlockBehavior.class);
            if (transport != null && transport.resolve(registries) == null) {
                problems.add("CraftEngine block " + block.id().asString() + " refers to transport type "
                        + transport.transportKey().asString() + ", which is not registered");
            }
        }
        return problems;
    }
}
