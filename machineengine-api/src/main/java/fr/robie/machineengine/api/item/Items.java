package fr.robie.machineengine.api.item;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.StorageCodec;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/**
 * Minecraft items as resources (ARCHITECTURE.md §23.4). There is one resource type, {@link #ITEM};
 * the concrete item is its {@link #DATA} component, so items added after MachineEngine started
 * (CraftEngine items load later) work too. Amounts are item counts.
 *
 * <p>MachineEngine registers these itself. The Paper module converts to and from {@code ItemStack}s.</p>
 */
public final class Items {
    public static final ResourceCategory CATEGORY = ResourceCategory.builder(MachineEngineKeys.key("items")).unit("items").build();

    public static final StorageCodec<ItemData> DATA_CODEC = StorageCodec.of(
            data -> DataMap.builder().put("id", data.item().asString()).put("bytes", Codecs.BYTES.encode(data.data())).build(),
            raw -> {
                if (!(raw instanceof DataMap map)) {
                    return Result.err(fr.robie.machineengine.api.error.MachineError.SerializationError.at("", "expected item data (map)"));
                }
                Result<Key> id = map.getString("id").flatMap(Codecs::parseKey);
                if (id.isErr()) return Result.err(id.error());
                return map.require("bytes").flatMap(Codecs.BYTES::decode).map(bytes -> new ItemData(id.value(), bytes));
            });

    public static final ResourceComponentType<ItemData> DATA =
            ResourceComponentType.of(MachineEngineKeys.key("item_data"), ItemData.class, DATA_CODEC);

    public static final ResourceType ITEM = ResourceType.of(MachineEngineKeys.key("item"), CATEGORY);

    private Items() {
    }

    public static @NotNull ResourceVariant variant(@NotNull ItemData data) {
        return ITEM.variant().with(DATA, data);
    }

    /** Matches items with this id, whatever their other data (name, enchantments …). */
    public static @NotNull ResourcePredicate ofItem(@NotNull Key itemId) {
        return ResourcePredicate.type(ITEM).and(ResourcePredicate.component(DATA, data -> data.item().equals(itemId)));
    }

    /** Matches items in the item tag {@code tag} (e.g. {@code c:ores/iron}), see {@link fr.robie.machineengine.api.resource.tag.ResourceTags#containsItem}. */
    public static @NotNull ResourcePredicate inTag(@NotNull Key tag) {
        java.util.Objects.requireNonNull(tag);
        return (variant, tags) -> {
            if (variant.type() != ITEM) return false;
            ItemData data = variant.components().get(DATA);
            return data != null && tags.containsItem(tag, data.item());
        };
    }

    /** Matches any item. */
    public static @NotNull ResourcePredicate anyItem() {
        return ResourcePredicate.type(ITEM);
    }
}
