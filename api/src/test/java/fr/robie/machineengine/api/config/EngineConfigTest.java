package fr.robie.machineengine.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void theOldBundledFileLoadsWithoutChanges(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("config.yml");
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/config/bundled-config-0.1.yml"))) {
            Files.copy(in, file);
        }
        String before = Files.readString(file, StandardCharsets.UTF_8);

        ConfigFile config = ConfigFile.load(file, EngineConfig.SPEC, Logger.getAnonymousLogger());

        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8), "every key is there, so nothing is written");
        assertEquals(256, config.get(EngineConfig.MAX_MACHINES_PER_CHUNK));
        assertEquals("machine", config.get(EngineConfig.DEFAULT_MENU));
    }
}
