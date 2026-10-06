package fr.robie.machineengine.api;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.config.EngineConfig;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds the registrations submitted before the engine starts and the running engine afterwards.
 * The only static mutable state of MachineEngine (§8.3). Addons use {@link MachineEngine#register}
 * and {@link MachineEngine#get()}; the platform uses the internal methods.
 */
@ApiStatus.Internal
public final class MachineEngineAccess {
    private static final List<AddonRegistration> PENDING = new ArrayList<>();
    private static @Nullable MachineEngine engine;
    private static volatile @Nullable ConfigValues config;
    private static boolean closed;

    private MachineEngineAccess() {
    }

    static synchronized void submit(@NotNull AddonRegistration registration) {
        Objects.requireNonNull(registration);
        if (closed) {
            throw new IllegalStateException("MachineEngine registration is closed; register " + registration.addon()
                    + " from its plugin's onLoad()");
        }
        PENDING.add(registration);
    }

    static synchronized @NotNull MachineEngine engine() {
        if (engine == null) throw new IllegalStateException("MachineEngine has not started yet");
        return engine;
    }

    static @NotNull ConfigValues config() {
        ConfigValues loaded = config;
        return loaded != null ? loaded : EngineConfig.SPEC.defaults();
    }

    /** The loaded {@code config.yml}; installed in {@code onLoad}, before addons register. */
    @ApiStatus.Internal
    public static void installConfig(@Nullable ConfigValues loaded) {
        config = loaded;
    }

    /** Closes registration and hands the submitted registrations to the platform. */
    @ApiStatus.Internal
    public static synchronized @NotNull List<AddonRegistration> closeRegistration() {
        closed = true;
        List<AddonRegistration> submitted = List.copyOf(PENDING);
        PENDING.clear();
        return submitted;
    }

    @ApiStatus.Internal
    public static synchronized void install(@Nullable MachineEngine running) {
        engine = running;
    }

    /** Resets everything, e.g. when the plugin is disabled. */
    @ApiStatus.Internal
    public static synchronized void reset() {
        PENDING.clear();
        engine = null;
        config = null;
        closed = false;
    }
}
