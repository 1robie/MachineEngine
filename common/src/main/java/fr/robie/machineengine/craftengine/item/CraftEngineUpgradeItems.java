package fr.robie.machineengine.craftengine.item;

import fr.robie.machineengine.api.item.UpgradeItem;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.upgrade.UpgradeItems;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.item.ItemDefinition;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Upgrade items on Paper (D40): an item's {@link UpgradeItem} behavior ({@code machineengine:upgrade}) names
 * its upgrade. Which item gives an upgrade back is looked up once per CraftEngine load.
 */
public final class CraftEngineUpgradeItems implements UpgradeItems.Access, Listener {
    private final Logger logger;
    private final Map<Key, Optional<ResourceVariant>> items = new ConcurrentHashMap<>();
    private final Set<Key> reported = ConcurrentHashMap.newKeySet();

    public CraftEngineUpgradeItems(@NotNull Logger logger) {
        this.logger = logger;
    }

    @Override
    public @Nullable Key upgradeOf(@NotNull ResourceVariant item) {
        ItemStack stack = PaperItems.toItemStack(item, 1);
        if (stack == null) return null;
        Key[] found = new Key[1];
        BukkitAdaptor.adapt(stack).getBehavior().ifPresent(behavior -> behavior.let(UpgradeItem.class, upgrade -> {
            if (found[0] == null) found[0] = upgrade.upgrade();
        }));
        return found[0];
    }

    @Override
    public @Nullable ResourceVariant itemOf(@NotNull Key upgrade) {
        return this.items.computeIfAbsent(upgrade, CraftEngineUpgradeItems::lookup).orElse(null);
    }

    private static Optional<ResourceVariant> lookup(Key upgrade) {
        Key id = null;
        for (ItemDefinition item : CraftEngineItems.loadedItems().values()) {
            Key[] named = new Key[1];
            item.behavior().let(UpgradeItem.class, behavior -> named[0] = behavior.upgrade());
            if (upgrade.equals(named[0])) {
                id = Key.key(item.id().asString());
                break;
            }
        }
        // Deprecated: the item whose id is the upgrade's key.
        ItemStack stack = PaperItems.itemOf(id != null ? id : upgrade);
        return stack == null ? Optional.empty() : Optional.of(PaperItems.toResource(stack).variant());
    }

    @Override
    public void usedIdFallback(@NotNull Key item) {
        if (this.reported.add(item)) {
            this.logger.warning("Item " + item.asString() + " is used as an upgrade because its id is the upgrade's key;"
                    + " give it the machineengine:upgrade item behavior (the id rule will be removed)");
        }
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        this.items.clear();
    }
}
