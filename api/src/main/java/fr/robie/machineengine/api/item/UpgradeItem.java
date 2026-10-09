package fr.robie.machineengine.api.item;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/**
 * Implemented by a CraftEngine item behavior that makes its item a machine upgrade (D40); MachineEngine's
 * own is {@code machineengine:upgrade}. Found on an item with {@code ItemBehavior#let(UpgradeItem.class, ...)}.
 */
public interface UpgradeItem {

    /** The key of the upgrade type one item installs. */
    @NotNull Key upgrade();
}
