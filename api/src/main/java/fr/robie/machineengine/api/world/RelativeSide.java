package fr.robie.machineengine.api.world;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A face relative to a machine's facing (ARCHITECTURE.md §11.2). The front is the face the machine
 * looks towards; left and right are as seen when standing behind the machine, looking the same way.
 */
public enum RelativeSide {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM;

    /**
     * @param facing the machine's facing. Facing up or down, the machine is a north-facing one turned
     *               over its west-east axis: its top then faces south (up) or north (down), its left
     *               and right stay west and east.
     */
    public @NotNull Direction toAbsolute(@NotNull Direction facing) {
        if (!facing.isHorizontal()) {
            boolean up = facing == Direction.UP;
            return switch (this) {
                case FRONT -> facing;
                case BACK -> facing.opposite();
                case LEFT -> Direction.WEST;
                case RIGHT -> Direction.EAST;
                case TOP -> up ? Direction.SOUTH : Direction.NORTH;
                case BOTTOM -> up ? Direction.NORTH : Direction.SOUTH;
            };
        }
        Direction front = facing;
        return switch (this) {
            case FRONT -> front;
            case BACK -> front.opposite();
            case LEFT -> front.counterClockwise();
            case RIGHT -> front.clockwise();
            case TOP -> Direction.UP;
            case BOTTOM -> Direction.DOWN;
        };
    }

    /** @return the relative side that {@code absolute} is for a machine facing {@code facing} */
    public static @Nullable RelativeSide fromAbsolute(@NotNull Direction facing, @NotNull Direction absolute) {
        for (RelativeSide side : values()) {
            if (side.toAbsolute(facing) == absolute) return side;
        }
        return null;
    }
}
