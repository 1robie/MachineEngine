package fr.robie.machineengine.api.item;

/**
 * Implemented by a CraftEngine item behavior that acts on machines (a configurator, a wrench, an item
 * filled from a machine). When the held item has such a behavior, a machine block lets the item's
 * {@code useOnBlock} run instead of opening its menu (D32).
 *
 * <p>Found on an item with {@code ItemBehavior#let(MachineTool.class, ...)}, so it works whichever
 * position the behavior has in the item's {@code behaviors:} list.</p>
 */
public interface MachineTool {

    /** @return whether this behavior wants clicks on machines; a behavior can turn it off by option */
    default boolean actsOnMachines() {
        return true;
    }
}
