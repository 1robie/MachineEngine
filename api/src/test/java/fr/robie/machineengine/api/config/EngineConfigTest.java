package fr.robie.machineengine.api.config;

import fr.robie.machineengine.api.resource.Energy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config.yml a new server gets. A change to {@link EngineConfig} changes this file: update
 * {@code config/engine-config.yml} in the test resources so the change shows up in review.
 */
class EngineConfigTest {
    @Test
    void aNewServerGetsTheExpectedFile(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("config.yml");
        ConfigFile.load(file, EngineConfig.SPEC, Logger.getAnonymousLogger());

        String expected;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/config/engine-config.yml"))) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertEquals(expected, read(file));
    }

    @Test
    void theOldBundledFileKeepsItsValuesAndGainsTheNewKeys(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("config.yml");
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/config/bundled-config-0.1.yml"))) {
            Files.copy(in, file);
        }
        String before = read(file);

        ConfigFile config = ConfigFile.load(file, EngineConfig.SPEC, Logger.getAnonymousLogger());

        String after = read(file);
        // Bukkit may re-indent a trailing comment when it saves; every line must survive, in order.
        assertEquals(lines(before), lines(after).subList(0, lines(before).size()), "the owner's part is kept:\n" + after);
        assertTrue(after.contains("resources:"), after);
        assertEquals(256, config.get(EngineConfig.MAX_MACHINES_PER_CHUNK));
        assertEquals("machine", config.get(EngineConfig.DEFAULT_MENU));
        assertEquals("J", config.get(Energy.UNIT));
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
