package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.MachineEngineKeys;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Why a machine was woken (ARCHITECTURE.md §17.2). Addons signal their own reasons with
 * {@link #external(Key)}; external reasons wake machines in any sleeping state except
 * {@link SchedulerState#DISABLED}.
 */
public record WakeReason(@NotNull Key key) {
    public static final WakeReason RESOURCE_INSERTED = builtIn("resource_inserted");
    public static final WakeReason RESOURCE_EXTRACTED = builtIn("resource_extracted");
    public static final WakeReason INPUT_CHANGED = builtIn("input_changed");
    public static final WakeReason OUTPUT_AVAILABLE = builtIn("output_available");
    public static final WakeReason NEIGHBOR_CHANGED = builtIn("neighbor_changed");
    public static final WakeReason NETWORK_CHANGED = builtIn("network_changed");
    public static final WakeReason UPGRADE_CHANGED = builtIn("upgrade_changed");
    public static final WakeReason RECIPES_RELOADED = builtIn("recipes_reloaded");
    /** Item or resource tags were reloaded: inputs may match recipes now. */
    public static final WakeReason TAGS_RELOADED = builtIn("tags_reloaded");
    public static final WakeReason CONFIG_CHANGED = builtIn("config_changed");
    public static final WakeReason TIMER = builtIn("timer");
    public static final WakeReason CHUNK_LOADED = builtIn("chunk_loaded");
    /** An admin changed the machine or woke it up with a command. */
    public static final WakeReason ADMIN = builtIn("admin");

    public WakeReason {
        Objects.requireNonNull(key);
    }

    /** An addon-defined reason, e.g. {@code magicplugin:moon_phase}. */
    public static @NotNull WakeReason external(@NotNull Key key) {
        if (key.namespace().equals(MachineEngineKeys.NAMESPACE)) {
            throw new IllegalArgumentException("External wake reasons must not use the machineengine namespace");
        }
        return new WakeReason(key);
    }

    public boolean isExternal() {
        return !this.key.namespace().equals(MachineEngineKeys.NAMESPACE);
    }

    private static WakeReason builtIn(String value) {
        return new WakeReason(MachineEngineKeys.key(value));
    }

    @Override
    public @NotNull String toString() {
        return this.key.asString();
    }
}
