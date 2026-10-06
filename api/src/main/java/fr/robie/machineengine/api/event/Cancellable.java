package fr.robie.machineengine.api.event;

/**
 * An event whose operation can be vetoed in the {@link EventPhase#PRE} phase. Listeners still
 * receive cancelled events and may un-cancel them.
 */
public interface Cancellable {

    boolean isCancelled();

    void setCancelled(boolean cancelled);
}
