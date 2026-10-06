package fr.robie.machineengine.api.platform;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link NmsAccess} of the running server (D28). MachineEngine installs the one matching the server
 * version when it loads, before any addon's {@code onLoad}. Like {@code MachineEngine.get()}, this is
 * documented static state set once at startup (§8.3).
 */
public final class Nms {
    private static volatile @Nullable NmsAccess access;

    private Nms() {
    }

    /**
     * @return the running server's version access
     * @throws IllegalStateException before MachineEngine has loaded
     */
    public static @NotNull NmsAccess get() {
        NmsAccess current = access;
        if (current == null) throw new IllegalStateException("MachineEngine has not loaded yet: no version access");
        return current;
    }

    /** Called by MachineEngine once, at load. */
    @ApiStatus.Internal
    public static void install(@NotNull NmsAccess implementation) {
        access = implementation;
    }
}
