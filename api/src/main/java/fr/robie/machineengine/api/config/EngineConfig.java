package fr.robie.machineengine.api.config;

import java.util.Map;

/**
 * MachineEngine's own settings ({@code plugins/MachineEngine/config.yml}), generated from {@link #SPEC}.
 * Read them with {@code MachineEngine.config().get(EngineConfig.MAX_MACHINES_PER_CHUNK)}.
 */
public final class EngineConfig {
    public static final ConfigKey<Integer> MAX_MACHINES_PER_CHUNK = ConfigKey.integer("limits.max-machines-per-chunk", 256, 0, 1_000_000)
            .comment("Most machines a single chunk may hold (players with machineengine.admin are exempt). 0 = no limit.");

    public static final ConfigKey<String> DEFAULT_MENU = ConfigKey.string("menus.default", "machine")
            .comment("Machine menus use zMenu (https://modrinth.com/plugin/zmenu). Menu files live in menus/.",
                    "A machine opens menus.by-type, else the file <namespace>_<value>.yml, else this default.");

    public static final ConfigKey<Map<String, String>> MENUS_BY_TYPE = ConfigKey.map("menus.by-type", Map.of())
            .comment("Menu file per machine type, e.g.", "  example:generator: machine");

    public static final ConfigKey<Boolean> BUKKIT_EVENT_BRIDGE = ConfigKey.bool("events.bukkit-bridge", true)
            .comment("Mirror machine events (create, load, unload, remove, process start/complete) as Bukkit events",
                    "(fr.robie.machineengine.paper.event) for plugins that do not use the MachineEngine API.",
                    "Events nobody listens to cost nothing. Restart to apply.")
            .restartOnly();

    public static final ConfigKey<Boolean> ALLOW_BREAKING_PLACEHOLDERS = ConfigKey.bool("placeholders.allow-breaking", false)
            .comment("Whether players without machineengine.admin.breakplaceholders may break placeholder machines",
                    "(machines whose addon is not installed). Breaking one destroys its data.");

    public static final ConfigSpec SPEC = ConfigSpec.builder()
            .header("MachineEngine configuration.",
                    "Missing settings are added with their defaults when the server starts and on /machineengine reload config.")
            .key(MAX_MACHINES_PER_CHUNK)
            .key(DEFAULT_MENU)
            .key(MENUS_BY_TYPE)
            .key(BUKKIT_EVENT_BRIDGE)
            .key(ALLOW_BREAKING_PLACEHOLDERS)
            .build();

    private EngineConfig() {
    }
}
