package fr.robie.machineengine.craftengine;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import org.jetbrains.annotations.Nullable;

/**
 * A CraftEngine reload builds new block states (with new behaviors) for the same state ids, while a
 * loaded block entity keeps pointing at the state it was loaded with. This finds the current one.
 */
final class LiveBlockStates {
    private LiveBlockStates() {
    }

    /** The state CraftEngine has now for {@code state}'s id, or {@code state} itself. */
    static @Nullable ImmutableBlockState current(@Nullable ImmutableBlockState state) {
        if (state == null) return null;
        ImmutableBlockState live = CraftEngine.instance().blockManager().getImmutableBlockState(state.customBlockState().registryId());
        return live != null ? live : state;
    }
}
