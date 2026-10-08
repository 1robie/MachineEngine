package fr.robie.machineengine.zmenu;

import fr.robie.machineengine.api.player.PlayerSetting;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated {@code player_settings.yml} (D37). */
class PlayerSettingsMenuTest {
    private static final Logger QUIET = quiet();
    private static final PlayerSetting<Boolean> HUD = PlayerSetting.bool(Key.key("rad", "hud"), true);
    private static final PlayerSetting<Long> VOLUME = PlayerSetting.whole(Key.key("rad", "volume"), 50, 0, 100, 10);

    private static Logger quiet() {
        Logger logger = Logger.getLogger("PlayerSettingsMenuTest");
        logger.setLevel(Level.OFF);
        return logger;
    }

    @Test
    void oneButtonPerSettingInsideTheBorder() {
        YamlConfiguration menu = YamlConfiguration.loadConfiguration(new java.io.StringReader(
                PlayerSettingsMenu.yaml(List.of(HUD, VOLUME), QUIET)));
        assertEquals(27, menu.getInt("size"));
        assertEquals("machineengine_player_setting", menu.getString("items.rad_hud.type"));
        assertEquals("rad:hud", menu.getString("items.rad_hud.setting"));
        assertEquals(10, menu.getInt("items.rad_hud.slot"));
        assertEquals(11, menu.getInt("items.rad_volume.slot"));
        assertTrue(menu.getStringList("items.rad_volume.item.lore").stream().anyMatch(line -> line.contains("%zmenu_machineengine_player_rad_volume%")));
    }

    @Test
    void manySettingsTakeMoreRowsUpToFour() {
        List<PlayerSetting<?>> settings = new ArrayList<>();
        for (int i = 0; i < 40; i++) settings.add(PlayerSetting.bool(Key.key("rad", "s" + i), true));
        YamlConfiguration menu = YamlConfiguration.loadConfiguration(new java.io.StringReader(PlayerSettingsMenu.yaml(settings, QUIET)));
        assertEquals(54, menu.getInt("size"));
        assertEquals(43, menu.getInt("items.rad_s27.slot"));
        assertTrue(!menu.contains("items.rad_s28"));
    }

    @Test
    void anEditedMenuIsKept(@TempDir Path folder) throws IOException {
        PlayerSettingsMenu.write(folder, List.of(HUD), QUIET);
        Path file = folder.resolve("player_settings.yml");
        assertTrue(Files.readString(file).contains("rad:hud"));

        // Still marked: rewritten with the settings of now.
        PlayerSettingsMenu.write(folder, List.of(HUD, VOLUME), QUIET);
        assertTrue(Files.readString(file).contains("rad:volume"));

        // The owner removed the marker: theirs stays.
        Files.writeString(file, "size: 9\n", StandardCharsets.UTF_8);
        PlayerSettingsMenu.write(folder, List.of(HUD), QUIET);
        assertEquals("size: 9\n", Files.readString(file));
    }
}
