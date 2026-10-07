package fr.robie.machineengine.craftengine.item;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.item.MachineTool;
import fr.robie.machineengine.api.item.ResourceItem;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.paper.PaperItemBehavior;
import fr.robie.machineengine.api.paper.PaperItemResources;
import fr.robie.machineengine.api.paper.ResourceNames;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.Energy;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.paper.Messages;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The CraftEngine item behaviors {@code machineengine:energy_item} and {@code machineengine:resource_item}
 * (D32): the item holds one resource, its amount kept in the item's data
 * ({@code PaperItemResources}). The resource key is not checked here, because CraftEngine reads item
 * configs before the resource registries are filled (D14); {@code CraftEngineIntegration.unboundContent}
 * reports unknown ones.
 *
 * <pre>{@code
 * behaviors:
 *   - type: machineengine:energy_item
 *     max-energy: 1000000     # required
 *     max-input: 10000        # optional, per transfer (default: no limit)
 *     max-output: 10000       # optional
 *   - type: machineengine:resource_item
 *     resource: mekanized:hydrogen   # required, a resource type key
 *     capacity: 24000                # required
 *     max-input: 1000                # optional
 *     max-output: 1000               # optional
 *     fill-from-machines: true       # optional: right-click a machine holding it to fill the item
 * }</pre>
 */
public final class ResourceItemBehavior extends PaperItemBehavior implements ResourceItem, MachineTool {
    public static final ItemBehaviorFactory<ResourceItemBehavior> ENERGY_FACTORY = (pack, path, id, section) ->
            new ResourceItemBehavior(Energy.KEY, positive(section, "max-energy"),
                    section.getLong("max-input", Long.MAX_VALUE), section.getLong("max-output", Long.MAX_VALUE), false);

    public static final ItemBehaviorFactory<ResourceItemBehavior> RESOURCE_FACTORY = (pack, path, id, section) ->
            new ResourceItemBehavior(Key.key(section.getNonEmptyString("resource")), positive(section, "capacity"),
                    section.getLong("max-input", Long.MAX_VALUE), section.getLong("max-output", Long.MAX_VALUE),
                    section.getBoolean("fill-from-machines", false));

    private final Key resource;
    private final long capacity;
    private final long maxInput;
    private final long maxOutput;
    private final boolean fillFromMachines;

    ResourceItemBehavior(@NotNull Key resource, long capacity, long maxInput, long maxOutput, boolean fillFromMachines) {
        this.resource = resource;
        this.capacity = capacity;
        this.maxInput = Math.max(0, maxInput);
        this.maxOutput = Math.max(0, maxOutput);
        this.fillFromMachines = fillFromMachines;
    }

    @Override
    public @NotNull Key resource() {
        return this.resource;
    }

    @Override
    public long baseCapacity() {
        return this.capacity;
    }

    @Override
    public long maxInput() {
        return this.maxInput;
    }

    @Override
    public long maxOutput() {
        return this.maxOutput;
    }

    @Override
    public boolean actsOnMachines() {
        return this.fillFromMachines;
    }

    /** {@code fill-from-machines}: takes what fits (at most {@code max-input}) from the first storage holding it. */
    @Override
    protected boolean use(@NotNull Use use) {
        Block block = use.block();
        if (!this.fillFromMachines || block == null) return false;
        MachineEngine engine = MachineEngine.get();
        MachineInstance machine = engine.machineAt(WorldPosition.of(block.getWorld().getKey(), block.getX(), block.getY(), block.getZ()))
                .orElse(null);
        ResourceType type = engine.registries().registry(Registries.RESOURCE_TYPE).get(this.resource).orElse(null);
        if (machine == null || type == null) return false;
        long room = Math.min(this.maxInput, PaperItemResources.capacity(use.item(), this.resource)
                - PaperItemResources.amount(use.item(), this.resource));
        if (room <= 0) return false;
        var context = machine.context();
        for (var storage : machine.inspect().storages().entrySet()) {
            if (!holds(storage.getValue(), type)) continue;
            long taken;
            try (Transaction transaction = context.transactions().openOuter()) {
                taken = context.storage(storage.getKey()).extract(type.variant(), room, transaction);
                transaction.commit();
            }
            if (taken <= 0) continue;
            PaperItemResources.add(use.item(), this.resource, taken);
            machine.wake(WakeReason.RESOURCE_EXTRACTED);
            use.player().sendActionBar(Messages.message("machineengine.item.filled", "§aFilled %s of %s",
                    Component.text(taken), MiniMessage.miniMessage().deserialize(ResourceNames.of(type.variant()))));
            return true;
        }
        return false;
    }

    private static boolean holds(List<StorageView> views, ResourceType type) {
        return views.stream().anyMatch(view -> !view.isEmpty() && view.variant().type() == type);
    }

    private static long positive(ConfigSection section, String key) {
        long value = section.getNonNullValue(key, ConfigConstants.ARGUMENT_LONG).getAsLong();
        // CraftEngine reports this one with the item and file; it has no "must be positive" message, so use the parse one
        if (value <= 0) throw new KnownResourceException("resource.argument.parser.long", section.assemblePath(key), value + " (must be > 0)");
        return value;
    }
}
