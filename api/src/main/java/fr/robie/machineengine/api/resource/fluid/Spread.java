package fr.robie.machineengine.api.resource.fluid;

/**
 * How a placed fluid spreads, compared with water.
 *
 * @param ticks ticks per spreading step, 5 (water) or more: a fluid can't spread faster than the water
 *              that carries it
 * @param range how many blocks it flows from a source on flat ground, 1 to 7 (water: 7)
 */
public record Spread(int ticks, int range) {
    public static final Spread WATER = new Spread(5, 7);

    public Spread {
        if (ticks < 5) throw new IllegalArgumentException("ticks must be 5 or more (water's speed), got " + ticks);
        if (range < 1 || range > 7) throw new IllegalArgumentException("range must be between 1 and 7, got " + range);
    }
}
