package fr.robie.machineengine.api.event;

import org.jetbrains.annotations.ApiStatus;

/** A registered listener. */
@ApiStatus.NonExtendable
public interface Subscription {

    void unsubscribe();

    boolean isActive();
}
