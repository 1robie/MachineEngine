package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.core.definition.DefinitionCompiler;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.resource.ItemTagSource;
import fr.robie.machineengine.api.paper.PaperItems;
import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * MachineEngine's view of CraftEngine items (E10, E11):
 *
 * <ul>
 *   <li>item ids: a CraftEngine item is identified by its custom id ({@code mekanized:dust_iron}),
 *       not by the vanilla material it is built on;</li>
 *   <li>definition item ids resolve CraftEngine items as well as vanilla materials;</li>
 *   <li>item tags: vanilla tags and the tags CraftEngine items declare ({@code settings.tags});</li>
 *   <li>CraftEngine loads its items after MachineEngine starts: recipes wait for its first load,
 *       and recipes and tags are reloaded on every CraftEngine (re)load.</li>
 * </ul>
 */
public final class CraftEngineItemBridge implements DefinitionCompiler.ItemResolver, ItemTagSource, Listener {
    private final Plugin plugin;
    private final Map<Key, Set<Key>> tags = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile Supplier<@Nullable CoreMachineEngine> engine = () -> null;

    public CraftEngineItemBridge(@NotNull Plugin plugin) {
        this.plugin = plugin;
        // A reload of MachineEngine while CraftEngine is already loaded.
        this.loaded = !CraftEngineItems.loadedItems().isEmpty();
        PaperItems.setIdResolver(CraftEngineItemBridge::idOf);
        PaperItems.setItemFactory(CraftEngineItemBridge::stackOf);
    }

    public void attach(@NotNull Supplier<@Nullable CoreMachineEngine> engine) {
        this.engine = engine;
    }

    /** The custom id of a CraftEngine item, else the material's key. */
    public static @NotNull Key idOf(@NotNull ItemStack stack) {
        net.momirealms.craftengine.core.util.Key custom = CraftEngineItems.getCustomItemId(stack);
        return custom != null ? Key.key(custom.namespace(), custom.value()) : stack.getType().getKey();
    }

    /** One CraftEngine item with this id, else the vanilla material, else {@code null}. */
    public static @Nullable ItemStack stackOf(@NotNull Key id) {
        var custom = CraftEngineItems.byId(net.momirealms.craftengine.core.util.Key.of(id.namespace(), id.value()));
        if (custom != null) return custom.buildBukkitItem();
        Material material = Material.matchMaterial(id.asString());
        return material == null || !material.isItem() || material.isAir() ? null : new ItemStack(material);
    }

    // -- definitions ------------------------------------------------------------------------------

    @Override
    public boolean ready() {
        return this.loaded;
    }

    @Override
    public @NotNull Result<ResourceVariant> item(@NotNull Key id) {
        var custom = CraftEngineItems.byId(net.momirealms.craftengine.core.util.Key.of(id.namespace(), id.value()));
        if (custom != null) {
            ItemStack stack = custom.buildBukkitItem();
            return Result.ok(PaperItems.toResource(stack, id).variant());
        }
        Material material = Material.matchMaterial(id.asString());
        if (material != null && material.isItem() && !material.isAir()) {
            return Result.ok(PaperItems.toResource(new ItemStack(material)).variant());
        }
        return Result.err(MachineError.of(DiagnosticCodes.DEFINITION_SKIPPED, "'" + id.asString() + "' is neither a CraftEngine nor a vanilla item"));
    }

    // -- item tags --------------------------------------------------------------------------------

    @Override
    public boolean contains(@NotNull Key tag, @NotNull Key item) {
        return this.tags.computeIfAbsent(tag, CraftEngineItemBridge::lookup).contains(item);
    }

    private static Set<Key> lookup(Key tag) {
        try {
            return BukkitItemManager.instance().itemIdsByTag(net.momirealms.craftengine.core.util.Key.of(tag.namespace(), tag.value()))
                    .stream().map(UniqueKey::key).map(key -> Key.key(key.namespace(), key.value())).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    // -- CraftEngine (re)loads --------------------------------------------------------------------

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        this.loaded = true;
        this.tags.clear();
        MachineEngine.scheduler(this.plugin).run(() -> {
            CoreMachineEngine running = this.engine.get();
            if (running == null) return;
            for (var type : List.of(Registries.ITEM_TAG, Registries.RESOURCE_TAG, Registries.RECIPE)) {
                var report = running.reload(type);
                if (report.hasErrors()) this.plugin.getLogger().warning("Reload of " + type.key().asString()
                        + " after CraftEngine loaded: " + System.lineSeparator() + report.format());
            }
            this.plugin.getLogger().info("Recipes and tags reloaded with CraftEngine's items ("
                    + running.registries().registry(Registries.RECIPE).size() + " recipes)");
            // Loaded machines and transports read their block configs again (D34).
            CraftEngineMachineHost host = CraftEngineMachineHost.current();
            if (host != null) host.blockConfigsReloaded();
        });
    }
}
