package fr.robie.machineengine.api.event;

/** Listeners run from {@link #FIRST} to {@link #LAST}; ties run in subscription order. */
public enum EventPriority {
    FIRST,
    EARLY,
    NORMAL,
    LATE,
    LAST
}
