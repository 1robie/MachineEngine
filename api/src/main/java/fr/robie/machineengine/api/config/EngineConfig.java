package fr.robie.machineengine.api.config;

import fr.robie.machineengine.api.resource.Energy;
import fr.robie.machineengine.api.resource.Fluids;

import java.util.List;
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
                    "(fr.robie.machineengine.api.paper.event) for plugins that do not use the MachineEngine API.",
                    "Events nobody listens to cost nothing. Restart to apply.")
            .restartOnly();

    public static final ConfigKey<Boolean> ALLOW_BREAKING_PLACEHOLDERS = ConfigKey.bool("placeholders.allow-breaking", false)
            .comment("Whether players without machineengine.admin.breakplaceholders may break placeholder machines",
                    "(machines whose addon is not installed). Breaking one destroys its data.");

    public static final ConfigKey<String> PLAYER_STORAGE = ConfigKey.string("players.storage", "machineengine:pdc")
            .comment("Where the data addons keep per player (radiation dose, settings, ...) is saved:",
                    "  machineengine:pdc    with the player's own data file (world/playerdata); admins cannot edit offline players",
                    "  machineengine:files  plugins/MachineEngine/players/, one file per player; works for offline players",
                    "Addons may add other storages. Switching does not move existing data. Restart to apply.")
            .restartOnly();

    public static final ConfigKey<Integer> PLAYER_SAVE_INTERVAL = ConfigKey.integer("players.save-interval", 60, 5, 3600)
            .comment("Seconds between saves of changed player data. Data is also saved when a player leaves and when the server stops.")
            .clamped();

    public static final ConfigKey<Integer> PLAYER_LOAD_TIMEOUT = ConfigKey.integer("players.load-timeout", 5, 1, 60)
            .comment("Seconds a joining player waits for their data (storages other than machineengine:pdc).",
                    "When it takes longer, the player is refused with a message rather than let in without their data.")
            .clamped();

    public static final ConfigKey<Map<String, String>> PLAYER_SETTING_DEFAULTS = ConfigKey.map("players.setting-defaults", Map.of())
            .comment("Default value of player settings (/machinesettings) for players who did not choose, e.g.",
                    "  \"mekanized:radiation_hud\": false");

    public static final ConfigKey<List<String>> PLAYER_LOCKED_SETTINGS = ConfigKey.strings("players.locked-settings", List.of())
            .comment("Player settings players may not change: they keep the value of setting-defaults (or the built-in one).");

    public static final ConfigKey<String> MESSAGES_DEFAULT_LANGUAGE = ConfigKey.string("messages.default-language", "en_us")
            .comment("Language of the console and of players whose client language has no file in lang/ (en_us, fr_fr...).",
                    "Each player reads lang/<their client's language>.yml, else the same language in another country, else this one.",
                    "Add a language by copying lang/en_us.yml to lang/<language code>.yml and translating it.");

    public static final ConfigKey<String> MESSAGES_PREFIX = ConfigKey.string("messages.prefix", "")
            .comment("Put before every chat message of MachineEngine (MiniMessage), e.g. \"<dark_gray>[<gold>Machines</gold>]</dark_gray> \".");

    public static final ConfigKey<Boolean> LOGGING_DEBUG = ConfigKey.bool("logging.debug", false)
            .comment("Log debug lines too: details for bug reports, too many for every day.");

    public static final ConfigSpec SPEC = ConfigSpec.builder()
            .header("MachineEngine configuration.",
                    "Missing settings are added with their defaults when the server starts and on /machineengine reload config.")
            .key(MAX_MACHINES_PER_CHUNK)
            .key(DEFAULT_MENU)
            .key(MENUS_BY_TYPE)
            .key(BUKKIT_EVENT_BRIDGE)
            .key(ALLOW_BREAKING_PLACEHOLDERS)
            .key(PLAYER_STORAGE)
            .key(PLAYER_SAVE_INTERVAL)
            .key(PLAYER_LOAD_TIMEOUT)
            .key(PLAYER_SETTING_DEFAULTS)
            .key(PLAYER_LOCKED_SETTINGS)
            .key(MESSAGES_DEFAULT_LANGUAGE)
            .key(MESSAGES_PREFIX)
            .key(LOGGING_DEBUG)
            .section("resources", "Resources MachineEngine provides to every addon and definition:",
                    "machineengine:energy, and the fluids machineengine:fluid/water and machineengine:fluid/lava.",
                    "Restart to apply.")
            .key(Energy.UNIT)
            .key(Energy.SCALE)
            .key(Fluids.UNIT)
            .key(Fluids.SCALE)
            .key(Fluids.BUCKET)
            .key(Fluids.WATER_COLOR)
            .key(Fluids.LAVA_COLOR)
            .build();

    private EngineConfig() {
    }
}
