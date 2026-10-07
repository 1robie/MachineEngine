package fr.robie.machineengine.craftengine.item;

import com.google.gson.JsonArray;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.item.ResourceItem;
import fr.robie.machineengine.api.paper.ResourceNames;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.Energy;
import fr.robie.machineengine.api.resource.ResourceType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.processor.ItemProcessorFactory;
import net.momirealms.craftengine.core.item.processor.SimpleNetworkItemProcessor;
import net.momirealms.craftengine.core.item.network.ItemPacketSource;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.core.util.Key;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The CraftEngine item processor {@code machineengine:resource_lore} (D32), for an item's
 * {@code client-bound-data}: each time the item is sent to a player, lore lines show what it holds
 * (energy, fluids, chemicals), translated on the player's client ({@code machineengine.lore.*} in the
 * engine pack's {@code lang.yml}), after the lore the item has. Nothing is stored on the item: the lines follow its current amounts,
 * and CraftEngine puts the item's own lore back when a creative client sends it.
 *
 * <pre>{@code
 * client-bound-data:
 *   machineengine:resource_lore: {}
 * }</pre>
 */
public final class ResourceLoreProcessor implements SimpleNetworkItemProcessor {
    public static final ResourceLoreProcessor INSTANCE = new ResourceLoreProcessor();
    public static final ItemProcessorFactory<ResourceLoreProcessor> FACTORY = value -> INSTANCE;

    private ResourceLoreProcessor() {
    }

    @Override
    public void apply(ItemBuildContext context) {
        Item item = context.item();
        List<Component> lines = new ArrayList<>();
        item.getBehavior().ifPresent(behavior -> behavior.let(ResourceItem.class, holder -> {
            Component line = line(item, holder);
            if (line != null) lines.add(line);
        }));
        if (lines.isEmpty()) return;
        JsonArray lore = item.loreJson().map(JsonArray::deepCopy).orElseGet(JsonArray::new);
        for (Component line : lines) {
            lore.add(GsonComponentSerializer.gson().serializeToTree(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
        }
        item.loreJson(lore);
    }

    private static @Nullable Component line(Item item, ResourceItem holder) {
        long capacity = holder.capacity(item);
        if (capacity <= 0) return null;
        Object stored = item.getTagAsJava("machineengine", "resources", holder.resource().asString());
        long amount = stored instanceof Number number ? number.longValue() : 0;
        ResourceType type = MachineEngine.get().registries().registry(Registries.RESOURCE_TYPE).get(holder.resource()).orElse(null);
        String unit = type == null ? "" : type.category().unit();
        Component amounts = Component.text(String.format(Locale.ROOT, "%,d", amount));
        Component max = Component.text(String.format(Locale.ROOT, "%,d", capacity));
        if (holder.resource().equals(Energy.KEY)) {
            return Component.translatable("machineengine.lore.energy", "§7Energy: %s / %s %s", amounts, max, Component.text(unit));
        }
        Component name = type == null ? Component.text(holder.resource().asString())
                : MiniMessage.miniMessage().deserialize(ResourceNames.of(type.variant()));
        return Component.translatable("machineengine.lore.resource", "§7%s: %s / %s %s", name, amounts, max, Component.text(unit));
    }

    @Override
    public boolean shouldSkip(ItemPacketSource source) {
        return source.canSkipLore;
    }

    @Override
    public Key componentType(Item item, ItemBuildContext context) {
        return DataComponentKeys.LORE;
    }

    @Override
    public Object[] nbtPath(Item item, ItemBuildContext context) {
        return new Object[] {"display", "Lore"};
    }

    @Override
    public String nbtPathString(Item item, ItemBuildContext context) {
        return "display.Lore";
    }
}
