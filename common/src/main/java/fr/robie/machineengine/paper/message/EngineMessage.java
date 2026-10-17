package fr.robie.machineengine.paper.message;

import fr.robie.messageflow.api.MessageTypeAdapter;
import fr.robie.messageflow.model.Message;
import fr.robie.messageflow.model.MessageType;
import fr.robie.messageflow.model.SimpleMessage;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * MachineEngine's chat and action bar messages (MESSAGEFLOW_PLAN.md B1), in {@code plugins/MachineEngine/lang/<language>.yml}
 * under these keys, each player reading their client's language. The English text here is the default; item lore and
 * menu texts stay CraftEngine translations ({@code lang.yml} of the CraftEngine pack).
 */
public enum EngineMessage implements Message {
    MISSING_ADDON("machineengine.machine.missing_addon", "<red>This machine belongs to an addon that is not installed (%type%)."),
    PLACEHOLDER_BREAK_DENIED("machineengine.machine.placeholder_break_denied",
            "<red>This machine (%type%) belongs to an addon that is not installed. Breaking it would destroy its data."),
    CHUNK_LIMIT("machineengine.machine.chunk_limit", "<red>This chunk already holds %count% machines (limit %limit%)."),
    DEBUG_ON("machineengine.debug.on", "<gold>Debug view on: dots show machine states (green active, yellow scheduled, "
            + "aqua waiting, orange blocked, blue sleeping, gray disabled, magenta placeholder, red error), ports (blue in, "
            + "orange out, purple both) and networks; look at a machine for details."),
    DEBUG_OFF("machineengine.debug.off", "<gray>Debug view off."),
    ITEM_FILLED("machineengine.item.filled", MessageType.ACTION_BAR, "<green>Filled %amount% of %resource%"),
    UPGRADE_INSTALLED("machineengine.upgrade.installed", MessageType.ACTION_BAR, "<green>Installed %count% × %upgrade%"),
    // Refusals (MachineAction.Outcome), sent as translatable components by the API and the core: %0% is the argument.
    UPGRADE_NOT_UPGRADE("machineengine.upgrade.not_upgrade", "<red>That is not an upgrade"),
    /** {@code %0%}: why (the engine's own text). */
    UPGRADE_REFUSED("machineengine.upgrade.refused", "<red>%0%"),
    UPGRADE_NO_ITEM("machineengine.upgrade.no_item", "<red>No item installs %0%; it stays in the machine"),
    MACHINE_NO_ACTION("machineengine.machine.no_action", "This machine has no action %0%"),
    MACHINE_ACTION_FAILED("machineengine.machine.action_failed", "That did not work (see the server log)"),
    WRENCH_BUSY("machineengine.wrench.busy", MessageType.ACTION_BAR, "<red>The machine is busy; try again"),
    READER_NOT_MACHINE("machineengine.reader.not_machine", "<gray>Not a machine"),
    READER_MACHINE("machineengine.reader.machine", "<gold>%machine%:"),
    READER_STORAGE("machineengine.reader.storage", "<gray>  %storage%: %amount% / %capacity% (%held%)"),
    /** The login screen: no player yet, so the default language. */
    PLAYER_DATA_NOT_LOADED("machineengine.player.data_not_loaded", "Your data could not be loaded. Please try again in a moment."),
    SETTINGS_PLAYERS_ONLY("machineengine.settings.players_only", "<red>Only players have settings."),
    SETTINGS_HEADER("machineengine.settings.header", "<gold>Your settings (/machinesettings \\<setting> \\<value>):"),
    SETTINGS_NONE("machineengine.settings.none", "<gray>There are no settings to change."),
    SETTINGS_ENTRY("machineengine.settings.entry", "<gray>%setting%: <white>%value%</white> <dark_gray>(%key%)</dark_gray> %description%"),
    SETTINGS_VALUE("machineengine.settings.value", "<gray>%setting%: <white>%value%"),
    SETTINGS_CHANGED("machineengine.settings.changed", "<green>%setting% is now %value%."),
    SETTINGS_INVALID("machineengine.settings.invalid", "<red>'%value%' is not %expected%."),
    SETTINGS_LOCKED("machineengine.settings.locked", "<red>%setting% is set by the server."),
    SETTINGS_UNKNOWN("machineengine.settings.unknown", "<red>There is no setting %name%. See /machinesettings."),

    // Admin commands (/machineengine). Values in %...% are data (positions, keys, inspection lines) and stay as they are.
    ADMIN_USAGE("machineengine.admin.usage", "<red>%usage%"),
    ADMIN_LINE("machineengine.admin.line", "<gray>%label%: <white>%value%"),
    ADMIN_NOT_RUNNING("machineengine.admin.not_running", "<red>MachineEngine is not running."),
    ADMIN_NO_MACHINE("machineengine.admin.no_machine", "<red>No machine is loaded at %position%."),
    ADMIN_PLACEHOLDER_MACHINE("machineengine.admin.placeholder_machine", "<red>No machine is loaded at %position% (a placeholder: its addon is missing)."),
    ADMIN_STATUS("machineengine.admin.status", "<gold>MachineEngine %phase%"),
    ADMIN_STATUS_ENABLED("machineengine.admin.status_enabled", "<gray>Enabled addons: <white>%addons%"),
    ADMIN_STATUS_DISABLED("machineengine.admin.status_disabled", "<gray>Disabled addons: <white>%addons%"),
    ADMIN_STATUS_MACHINES("machineengine.admin.status_machines",
            "<gray>Loaded machines: <white>%count% (%active% active, %sleeping% sleeping, %placeholders% placeholders)"),
    ADMIN_STATUS_PROBLEMS("machineengine.admin.status_problems", "<gray>Startup problems: <white>%errors% errors, %warnings% warnings"),
    ADMIN_DEBUG_PLAYERS_ONLY("machineengine.admin.debug_players_only", "<red>Only players can see the debug view."),
    ADMIN_PLACEHOLDERS_NONE("machineengine.admin.placeholders_none", "<gold>No loaded machine keeps data of missing content."),
    ADMIN_PLACEHOLDERS_FOUND("machineengine.admin.placeholders_found", "<gold>%count% loaded machine(s) keep data of missing content:"),
    ADMIN_PLACEHOLDERS_MISSING_TYPE("machineengine.admin.placeholders_missing_type", "<gray>  %position%: <white>placeholder (%type% is not registered)"),
    ADMIN_PLACEHOLDERS_MISSING_DATA("machineengine.admin.placeholders_missing_data",
            "<gray>  %position%: <white>%type%: locked slots %slots%, unknown data %data%"),
    ADMIN_PLACEHOLDERS_HINT("machineengine.admin.placeholders_hint",
            "<gray>Delete with /machineengine placeholders purge \\<x y z> [world] (irreversible)."),
    ADMIN_PURGE_CONFIRM("machineengine.admin.purge_confirm", "<yellow>This deletes the missing content's data at %position% for good "
            + "(a placeholder machine is removed with its block). Run the command again within 10 seconds to confirm."),
    ADMIN_PURGE_NOTHING("machineengine.admin.purge_nothing", "<gray>Nothing to purge at %position%."),
    ADMIN_PURGE_DONE("machineengine.admin.purge_done", "<green>Purged: %result%."),
    ADMIN_NO_TRANSPORT("machineengine.admin.no_transport", "<red>No loaded transport at %position%."),
    ADMIN_NETWORK_ACTIVE("machineengine.admin.network_active",
            "<gold>Network %type%: %transports% transports, rate %rate%/tick, active, moved %moved% last tick"),
    ADMIN_NETWORK_SLEEPING("machineengine.admin.network_sleeping",
            "<gold>Network %type%: %transports% transports, rate %rate%/tick, sleeping, moved %moved% last tick"),
    ADMIN_NETWORK_ENDPOINT("machineengine.admin.network_endpoint", "<gray>  endpoint: <white>%endpoint%"),
    /** One line of an inspection ({@code /machineengine inspect}); the line is data, also written to logs. */
    ADMIN_INSPECT_LINE("machineengine.admin.inspect_line", "<white>%line%"),
    ADMIN_NO_REGISTRY("machineengine.admin.no_registry", "<red>No registry %registry%."),
    ADMIN_REGISTRY("machineengine.admin.registry", "<gold>Registry %registry% (%count% entries)"),
    ADMIN_REGISTRY_ALIAS("machineengine.admin.registry_alias", "<gray>%from% → %to%: <white>alias by %owner%"),
    ADMIN_CONFIG_ERROR("machineengine.admin.config_error", "<red>config.yml could not be read; the previous settings stay live: %error%"),
    ADMIN_CONFIG_RELOADED("machineengine.admin.config_reloaded", "<green>Reloaded config.yml."),
    ADMIN_CONFIG_CHANGED("machineengine.admin.config_changed", "<gray>Changed: <white>%keys%"),
    ADMIN_CONFIG_ADDED("machineengine.admin.config_added", "<gray>Added with defaults: <white>%keys%"),
    ADMIN_CONFIG_RESTART("machineengine.admin.config_restart", "<yellow>Restart to apply: %keys%"),
    ADMIN_CONFIG_INVALID("machineengine.admin.config_invalid", "<yellow>Invalid, using the default (see the console): %keys%"),
    ADMIN_RELOAD_REJECTED("machineengine.admin.reload_rejected", "<red>Reload of %type% rejected; the previous content stays live."),
    ADMIN_RELOADED("machineengine.admin.reloaded", "<green>Reloaded %type%."),
    ADMIN_FLUIDS_RELOADED("machineengine.admin.fluids_reloaded",
            "<green>Reloaded fluids.yml: %count% fluid(s) overridden. Physics, mixing and effects apply now; looks after the resource pack is rebuilt; fog after players rejoin."),
    /** {@code %message%}: what the pack builder did (the console has the details). */
    ADMIN_FLUIDS_PACK("machineengine.admin.fluids_pack", "<gray>%message% Send it to players with /ce reload pack."),
    ADMIN_FLUIDS_OFF("machineengine.admin.fluids_off", "<gray>World fluids are off (world-fluids.enabled in config.yml)."),
    ADMIN_FLUID_STATUS("machineengine.admin.fluid_status", "<gray>World fluids: %fluids% placeable, %looks% looks (%missing% without a key), "
            + "uniform water %uniform%, client biomes %base% + %entries%; %updates% biome updates sent; %tracked% fluid blocks loaded here."),
    ADMIN_FLUID_BLOCK("machineengine.admin.fluid_block", "<gray>%x% %y% %z%: <white>%fluid%</white>; its color area %cell% shows %area%."),
    ADMIN_FLUID_NEIGHBOURS("machineengine.admin.fluid_neighbours", "<gray>The 8 blocks around (Sodium blends them into the corners): "
            + "%same% show the same, %plain% plain water, %other% something else."),
    ADMIN_FLUID_BLOCK_REFUSED("machineengine.admin.fluid_block_refused", "<gray>Plain water there: %reason%"),
    ADMIN_FLUID_UNKNOWN("machineengine.admin.fluid_unknown", "<red>No placeable fluid %fluid%. Placeable: %fluids%"),
    ADMIN_FLUID_FILLED("machineengine.admin.fluid_filled", "<green>%count% water blocks are now %fluid%."),
    ADMIN_FLUID_NOT_WATER("machineengine.admin.fluid_not_water", "<red>Look at water (16 blocks or closer)."),
    ADMIN_FLUID_CLEARED("machineengine.admin.fluid_cleared", "<green>The placeable fluids of %world% are turning back into plain water."),
    ADMIN_FLUID_SHOW_ON("machineengine.admin.fluid_show_on", "<gray>Placement grid for %fluid% at your feet: green can go, red blocked, "
            + "aqua already there. /machineengine fluid show to stop."),
    ADMIN_FLUID_SHOW_OFF("machineengine.admin.fluid_show_off", "<gray>Placement grid off."),
    ADMIN_FLUID_PLAYERS_ONLY("machineengine.admin.fluid_players_only", "<red>Only players can do that."),
    ADMIN_FLUID_NOT_RUNNING("machineengine.admin.fluid_not_running", "<red>World fluids are not running on this server (see the startup log)."),
    FLUID_SHOW_BAR("machineengine.fluid.show_bar", MessageType.ACTION_BAR, "<gray>%fluid%: %allowed% can go, %refused% blocked"),
    FLUID_BUCKET_NAME("machineengine.fluid.bucket_name", "<white>%fluid% bucket"),
    // Why a fluid may not go somewhere (WorldFluids.Placement): bucket refusals and WorldFluids.canPlace.
    FLUID_NOT_PLACEABLE("machineengine.fluid.not_placeable", "<red>%fluid% can't be placed in the world"),
    FLUID_NOT_HERE("machineengine.fluid.not_here", "<red>A fluid only goes into air or water"),
    FLUID_NO_KEY("machineengine.fluid.no_key", "<red>%fluid% can't be placed: too many different fluid looks (see the server log)"),
    FLUID_TOO_CLOSE("machineengine.fluid.too_close", "<red>%other% is too close: different fluids keep about %blocks% blocks apart"),
    FLUID_AREA_OF_FLUID("machineengine.fluid.area_of_fluid", "<red>Plain water can't go this close to %other%"),
    FLUID_AREA_OF_OTHER("machineengine.fluid.area_of_other", "<red>Another fluid is too close"),
    FLUID_AREA_OF_WATER("machineengine.fluid.area_of_water", "<red>Plain water is too close: a fluid keeps a small gap from water"),
    ADMIN_NO_PROBLEMS("machineengine.admin.no_problems", "<green>No problems found."),
    /** A problem of a report; the text is data (diagnostic codes and details). */
    ADMIN_PROBLEM_ERROR("machineengine.admin.problem_error", "<red>%problem%"),
    ADMIN_PROBLEM_WARNING("machineengine.admin.problem_warning", "<yellow>%problem%"),
    /** A line of {@code /machineengine check} (dev checks): data, read by scripts. */
    ADMIN_CHECK("machineengine.admin.check", "<yellow>%result%"),
    ADMIN_STORAGE_WOKE("machineengine.admin.storage_woke", "<green>Woke up %type% at %position%; it was %state%."),
    ADMIN_STORAGE_NO_RESOURCE("machineengine.admin.storage_no_resource", "<red>No resource or item %resource%."),
    ADMIN_STORAGE_PUT("machineengine.admin.storage_put", "<green>Put %inserted% of %amount% %resource% into %storage%."),
    ADMIN_STORAGE_PUT_PARTIAL("machineengine.admin.storage_put_partial",
            "<yellow>Put %inserted% of %amount% %resource% into %storage% (it is full, or does not take %resource%)."),
    ADMIN_STORAGE_REMOVED("machineengine.admin.storage_removed", "<green>Removed %removed% from %storage%."),
    ADMIN_STORAGE_UNKNOWN("machineengine.admin.storage_unknown", "<red>%type% has no storage '%storage%'. Storages: %storages%"),
    ADMIN_PLAYERS_NO_KEY("machineengine.admin.players_no_key", "<red>No player data key %key%. Keys: %keys%"),
    ADMIN_PLAYERS_NEVER_PLAYED("machineengine.admin.players_never_played", "<red>%player% has never played here."),
    ADMIN_PLAYERS_LOAD_FAILED("machineengine.admin.players_load_failed", "<red>Could not load the data of %player%: %error%"),
    ADMIN_PLAYERS_OFFLINE_UNREADABLE("machineengine.admin.players_offline_unreadable", "<red>%player% is offline, and the player data "
            + "storage cannot read offline players (players.storage: machineengine:files can)."),
    ADMIN_PLAYERS_SAVE_FAILED("machineengine.admin.players_save_failed", "<red>Could not save: %error%"),
    ADMIN_PLAYERS_OFFLINE_NAME("machineengine.admin.players_offline_name", "%player% (offline)"),
    ADMIN_PLAYERS_GET("machineengine.admin.players_get", "<gray>%player% %key%: <white>%value%"),
    ADMIN_PLAYERS_SET_PROBLEM("machineengine.admin.players_set_problem", "<red>%problem%."),
    ADMIN_PLAYERS_SET("machineengine.admin.players_set", "<green>Set %key% of %player% to %value%."),
    ADMIN_PLAYERS_RESET("machineengine.admin.players_reset", "<green>Reset %key% of %player%."),
    ADMIN_PLAYERS_LIST("machineengine.admin.players_list", "<gold>Player data of %player% (storage %storage%)"),
    ADMIN_PLAYERS_ENTRY_SETTING("machineengine.admin.players_entry_setting", "<gray>  %key%: <white>%value% (setting)"),
    ADMIN_PLAYERS_ENTRY_LOCKED("machineengine.admin.players_entry_locked", "<gray>  %key%: <white>%value% (setting, locked)"),
    ADMIN_PLAYERS_ENTRY_SAVED("machineengine.admin.players_entry_saved", "<gray>  %key%: <white>%value% (saved)"),
    ADMIN_PLAYERS_ENTRY_SESSION("machineengine.admin.players_entry_session", "<gray>  %key%: <white>%value% (session)"),
    ADMIN_PLAYERS_ENTRY_UNKNOWN("machineengine.admin.players_entry_unknown", "<gray>  %key%: <white>kept, not registered (addon missing?)"),
    /** For the API's position arguments ({@code PositionArguments}), rendered by the translator. */
    ADMIN_POSITION_NEEDED("machineengine.admin.position_needed", "<red>Give a position: \\<x> \\<y> \\<z> [world]."),
    ADMIN_POSITION_LOOK("machineengine.admin.position_look", "<red>Look at a block, or give a position."),
    /** {@code %0%}: the world name given. */
    ADMIN_POSITION_UNKNOWN_WORLD("machineengine.admin.position_unknown_world", "<red>Unknown world '%0%'."),
    ADMIN_POSITION_WORLD("machineengine.admin.position_world", "<red>Give a world.");

    private final String key;
    private final List<SimpleMessage> defaults;
    private volatile List<? extends MessageTypeAdapter> loaded;

    EngineMessage(String key, String text) {
        this(key, MessageType.TCHAT, text);
    }

    EngineMessage(String key, MessageType type, String text) {
        this.key = key;
        this.defaults = List.of(new SimpleMessage(type, List.of(text)));
        this.loaded = this.defaults;
    }

    @Override
    public @NotNull String key() {
        return this.key;
    }

    @Override
    public @NotNull List<? extends MessageTypeAdapter> defaults() {
        return this.defaults;
    }

    @Override
    public @NotNull List<? extends MessageTypeAdapter> loaded() {
        return this.loaded;
    }

    @Override
    public void setLoaded(@NotNull List<? extends MessageTypeAdapter> loaded) {
        this.loaded = loaded;
    }
}
