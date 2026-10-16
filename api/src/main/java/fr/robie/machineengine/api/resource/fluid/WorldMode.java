package fr.robie.machineengine.api.resource.fluid;

/** Where a fluid can exist outside machines and pipes (docs/FLUID_TYPES_PLAN.md §2.2). */
public enum WorldMode {
    /** Only in tanks, pipes and items: it has no block in the world. */
    NONE,
    /** A vanilla fluid (water, lava): its own world block, handled by the game. */
    VANILLA,
    /**
     * A custom fluid that can be placed and flows in the world: carried by water blocks, tracked by
     * MachineEngine, drawn with its own look (D46).
     */
    PLACEABLE
}
