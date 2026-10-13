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
    SETTINGS_UNKNOWN("machineengine.settings.unknown", "<red>There is no setting %name%. See /machinesettings.");

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
