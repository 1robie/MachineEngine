package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.item.ResourceItem;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.craftengine.item.ResourceItemBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Registration of MachineEngine's CraftEngine behaviors and checks of block and item configs (§22.2, D32). */
public final class CraftEngineIntegration {

    private CraftEngineIntegration() {
    }

    /** Must run in MachineEngine's {@code onLoad}: CraftEngine reads its configs in its {@code onEnable}. */
    public static void registerBehaviors() {
        BlockBehaviors.register(CraftEngineKeys.toCraftEngine(MachineEngineKeys.key("machine")), MachineBlockBehavior.FACTORY);
        BlockBehaviors.register(CraftEngineKeys.toCraftEngine(MachineEngineKeys.key("transport")), TransportBlockBehavior.FACTORY);
        ItemBehaviors.register(CraftEngineKeys.toCraftEngine(MachineEngineKeys.key("energy_item")), ResourceItemBehavior.ENERGY_FACTORY);
        ItemBehaviors.register(CraftEngineKeys.toCraftEngine(MachineEngineKeys.key("resource_item")), ResourceItemBehavior.RESOURCE_FACTORY);
    }

    /** @return whether {@code state} is a block with the {@code machineengine:machine} behavior */
    public static boolean isMachineBlock(@NotNull ImmutableBlockState state) {
        return state.behavior().getFirst(MachineBlockBehavior.class) != null;
    }

    /**
     * @return one message per CraftEngine block whose {@code machine:} or {@code transport:} key is not
     *         registered (D14), and per item holding a resource type that is not registered (D32); such
     *         blocks stay inert, such items hold nothing of it
     */
    public static @NotNull List<String> unboundContent(@NotNull RegistryAccess registries) {
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
        for (ItemDefinition item : CraftEngineItems.loadedItems().values()) {
            item.behavior().let(ResourceItem.class, holder -> {
                if (registries.registry(Registries.RESOURCE_TYPE).get(holder.resource()).isEmpty()) {
                    problems.add("CraftEngine item " + item.id().asString() + " holds resource type "
                            + holder.resource().asString() + ", which is not registered");
                }
            });
        }
        return problems;
    }
}
