package fr.robie.machineengine.zmenu;

import fr.robie.machineengine.api.player.PlayerSetting;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@code menus/player_settings.yml} (D37), opened by {@code /machinesettings}: one
 * {@code machineengine_player_setting} button per registered setting. MachineEngine writes it at every
 * start while its first line is {@link #MARKER}, so settings of new addons show up; a server owner who
 * edits it removes that line and keeps their version.
 */
final class PlayerSettingsMenu {
    static final String NAME = "player_settings";
    static final String MARKER = "# Written by MachineEngine at every start. Delete this line to keep your own version.";
    /** The inner slots of a 6-row menu, row by row, inside a border. */
    private static final int COLUMNS = 7, MAX_ROWS = 4;

    private PlayerSettingsMenu() {
    }

    static void write(@NotNull Path folder, @NotNull List<PlayerSetting<?>> settings, @NotNull Logger logger) {
        Path file = folder.resolve(NAME + ".yml");
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !lines.getFirst().equals(MARKER)) return;
            }
            Files.createDirectories(folder);
            Files.writeString(file, yaml(settings, logger), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("Could not write the menu " + file + ": " + e.getMessage());
        }
    }

    static String yaml(List<PlayerSetting<?>> settings, Logger logger) {
        int max = COLUMNS * MAX_ROWS;
        if (settings.size() > max) {
            logger.warning("Only the first " + max + " player settings fit in " + NAME + ".yml; edit it to arrange the others");
        }
        int shown = Math.min(settings.size(), max);
        int rows = Math.max(1, (shown + COLUMNS - 1) / COLUMNS) + 2;
        StringBuilder yaml = new StringBuilder(MARKER).append('\n')
                .append("# Player settings (/machinesettings). Button type machineengine_player_setting:\n")
                .append("#   setting: <namespace>:<id>   left click: next value, right click: previous\n")
                .append("# Value placeholder: %zmenu_machineengine_player_<namespace>_<id>%\n\n")
                .append("size: ").append(rows * 9).append('\n')
                .append("name: \"<lang:machineengine.settings.title>\"\n\n")
                .append("items:\n")
                .append("  background:\n    is-permanent: true\n    item:\n      material: GRAY_STAINED_GLASS_PANE\n      name: \" \"\n")
                .append("    slots:\n      - 0-").append(rows * 9 - 1).append('\n');
        if (shown == 0) {
            yaml.append("  none:\n    slot: 13\n    item:\n      material: BARRIER\n      name: \"<!i><lang:machineengine.settings.none>\"\n");
        }
        for (int index = 0; index < shown; index++) {
            PlayerSetting<?> setting = settings.get(index);
            int slot = (index / COLUMNS + 1) * 9 + 1 + index % COLUMNS;
            String id = setting.key().namespace() + "_" + setting.key().value().replace('/', '_');
            yaml.append("  ").append(id).append(":\n")
                    .append("    type: machineengine_player_setting\n")
                    .append("    setting: ").append(setting.key().asString()).append('\n')
                    .append("    slot: ").append(slot).append('\n')
                    .append("    item:\n")
                    .append("      material: ").append(material(setting)).append('\n')
                    .append("      name: \"<!i><yellow><lang:").append(setting.nameKey()).append(">\"\n")
                    .append("      lore:\n")
                    .append("        - \"<!i><gray><lang:").append(setting.descriptionKey()).append(">\"\n")
                    .append("        - \"<!i><gray><lang:machineengine.settings.menu_value> <white>%zmenu_machineengine_player_").append(id).append("%\"\n")
                    .append("        - \"<!i><dark_gray><lang:machineengine.settings.menu_click>\"\n");
        }
        return yaml.toString();
    }

    private static String material(PlayerSetting<?> setting) {
        Object value = setting.defaultValue();
        if (value instanceof Boolean) return "LEVER";
        if (value instanceof Enum<?>) return "COMPARATOR";
        return "REPEATER";
    }
}
