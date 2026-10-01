package fr.robie.machineengine.api.event;

public enum EventPhase {
    /** Before the operation; listeners may cancel or modify where the event allows it. */
    PRE,
    /** After the operation committed; observe only. */
    POST
}
