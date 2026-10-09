package fr.robie.machineengine.api.upgrade;

import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.resource.ResourceVariant;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Which item is which upgrade (D40). An item installs an upgrade when it has an
 * {@link fr.robie.machineengine.api.item.UpgradeItem} behavior ({@code machineengine:upgrade}); the platform
 * reads behaviors ({@link #setAccess}), headless tests install their own. Deprecated fallback: an item
 * whose id is a registered upgrade key is that upgrade.
 */
public final class UpgradeItems {

    /** Reads upgrade behaviors from items. */
    public interface Access {
        /** The upgrade key named by {@code item}'s upgrade behavior, or null if it has none. */
        @Nullable Key upgradeOf(@NotNull ResourceVariant item);

        /** One item that installs {@code upgrade} (an item with that behavior, else the item whose id is the key), or null. */
        @Nullable ResourceVariant itemOf(@NotNull Key upgrade);

        /** {@code item} was taken for an upgrade only because its id is the upgrade's key (to report once). */
        default void usedIdFallback(@NotNull Key item) {
        }
    }

    private static volatile @Nullable Access access;

    private UpgradeItems() {
    }

    @ApiStatus.Internal
    public static void setAccess(@Nullable Access value) {
        access = value;
    }

    /** The upgrade {@code item} installs, among {@code upgrades}; null if it is not an upgrade. */
    public static @Nullable UpgradeType upgradeOf(@NotNull ResourceVariant item, @NotNull Registry<UpgradeType> upgrades) {
        if (item.type() != Items.ITEM) return null;
        Access current = access;
        Key named = current == null ? null : current.upgradeOf(item);
        if (named != null) return upgrades.get(named).orElse(null);
        ItemData data = item.components().get(Items.DATA);
        if (data == null) return null;
        UpgradeType byId = upgrades.get(data.item()).orElse(null);
        if (byId != null && current != null) current.usedIdFallback(data.item());
        return byId;
    }

    /** One item that installs {@code upgrade}, or null if there is none (or no platform). */
    public static @Nullable ResourceVariant itemOf(@NotNull UpgradeType upgrade) {
        Access current = access;
        return current == null ? null : current.itemOf(upgrade.key());
    }
}
