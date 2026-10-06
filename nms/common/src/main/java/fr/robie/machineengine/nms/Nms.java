package fr.robie.machineengine.nms;

import org.jetbrains.annotations.NotNull;

/**
 * The {@link NmsAccess} of the running server. The plugin installs the one matching the server version
 * at load ({@code NmsLoader}); until then, and in headless tests, it is {@link ReflectiveNms}. Like
 * {@code MachineEngine.get()}, this is documented static state set once at startup (§8.3).
 */
public final class Nms {
    private static volatile NmsAccess access = new ReflectiveNms();

    private Nms() {
    }

    public static @NotNull NmsAccess get() {
        return access;
    }

    /** Called by the plugin once, before anything uses {@link #get()}. */
    public static void install(@NotNull NmsAccess implementation) {
        access = implementation;
    }
}
