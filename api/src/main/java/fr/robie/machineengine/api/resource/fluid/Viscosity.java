package fr.robie.machineengine.api.resource.fluid;

/**
 * How a fluid moves things inside it, compared with water (all factors 1 and no damping = water).
 *
 * <p>Players are moved by their client, so their speed, gravity and jump are attribute modifiers the
 * client predicts (smooth). Water's swimming acceleration can only be raised that way ({@code swim}),
 * never lowered: fluids thicker than water use {@code damping}, which the server applies to the
 * player's velocity (it can stutter a little). Other entities (mobs, items, boats) are moved by the
 * server, so {@code entityDrag} is smooth for them.</p>
 *
 * @param speed      walking and swimming speed multiplier ({@code movement_speed}), above 0
 * @param gravity    gravity multiplier (1.20.5+), 0 or more
 * @param jump       jump strength multiplier (1.20.5+), 0 or more
 * @param swim       added {@code water_movement_efficiency} (1.21+), 0 to 1: above 0 swims faster than water
 * @param damping    player velocity lost every 2 ticks inside, 0 to 0.9 (thick fluids)
 * @param entityDrag velocity other entities lose every 2 ticks inside, 0 to 0.9
 */
public record Viscosity(double speed, double gravity, double jump, double swim, double damping, double entityDrag) {
    /** Water: changes nothing. */
    public static final Viscosity WATER = new Viscosity(1, 1, 1, 0, 0, 0);

    public Viscosity {
        check("speed", speed, 0.001, 100);
        check("gravity", gravity, 0, 100);
        check("jump", jump, 0, 100);
        check("swim", swim, 0, 1);
        check("damping", damping, 0, 0.9);
        check("entityDrag", entityDrag, 0, 0.9);
    }

    /** Whether this changes nothing compared with water. */
    public boolean isWater() {
        return equals(WATER);
    }

    private static void check(String name, double value, double min, double max) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ", got " + value);
        }
    }
}
