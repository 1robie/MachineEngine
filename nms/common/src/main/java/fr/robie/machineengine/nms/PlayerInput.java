package fr.robie.machineengine.nms;

/** The movement keys a player holds this tick. */
public record PlayerInput(boolean forward, boolean backward, boolean left, boolean right,
                          boolean jump, boolean sneak, boolean sprint) {
}
