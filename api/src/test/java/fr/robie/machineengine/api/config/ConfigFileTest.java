package fr.robie.machineengine.api.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The config loader: creating and completing files, invalid values, unknown keys and reloads. */
class ConfigFileTest {
    enum Mode { SAFE, FAST_MODE }

    static final ConfigKey<Integer> LIMIT = ConfigKey.integer("limits.per-chunk", 256, 0, 100_000).comment("Most machines per chunk.");
    static final ConfigKey<Boolean> BRIDGE = ConfigKey.bool("events.bridge", true).restartOnly();
    static final ConfigKey<String> UNIT = ConfigKey.string("resources.energy.unit", "J").comment("Shown after energy amounts.").restartOnly();
    static final ConfigKey<Double> RATIO = ConfigKey.decimal("ratio", 0.5, 0, 1);
    static final ConfigKey<Mode> MODE = ConfigKey.choice("mode", Mode.SAFE);
    static final ConfigKey<List<String>> NAMES = ConfigKey.strings("names", List.of("a", "b"));
    static final ConfigKey<Map<String, String>> BY_TYPE = ConfigKey.map("menus.by-type", Map.of());
    static final ConfigSpec SPEC = ConfigSpec.builder()
            .header("Test configuration.")
            .section("resources", "Resources every addon shares.")
            .keys(List.of(LIMIT, BRIDGE, UNIT, RATIO, MODE, NAMES, BY_TYPE))
            .build();

    @TempDir
    Path folder;
    Path file;
    final List<LogRecord> logs = new ArrayList<>();
    final Logger logger = Logger.getAnonymousLogger();

    @BeforeEach
    void setUp() {
        this.file = this.folder.resolve("config.yml");
        this.logger.setUseParentHandlers(false);
        this.logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                ConfigFileTest.this.logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private void write(String... lines) throws IOException {
        Files.writeString(this.file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private String text() throws IOException {
        return Files.readString(this.file, StandardCharsets.UTF_8);
    }

    private long warnings() {
        return this.logs.stream().filter(record -> record.getLevel() == Level.WARNING).count();
    }

    @Test
    void aMissingFileIsCreatedWithEveryKeyAndComment() throws IOException {
        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);

        String text = text();
        assertTrue(text.startsWith("# Test configuration."), text);
        assertTrue(text.contains("# Most machines per chunk.\n  per-chunk: 256"), text);
        assertTrue(text.contains("# Resources every addon shares.\nresources:"), text);
        assertTrue(text.contains("# Shown after energy amounts.\n    unit: J"), text);
        assertTrue(text.contains("mode: safe"), text);
        assertTrue(text.contains("by-type: {}"), text);
        assertEquals(256, config.get(LIMIT));
        assertEquals(Mode.SAFE, config.get(MODE));
        assertEquals(List.of("a", "b"), config.get(NAMES));

        // A second load reads every key back and changes nothing.
        ConfigFile again = ConfigFile.load(this.file, SPEC, this.logger);
        assertEquals(text, text());
        assertEquals(0, warnings());
        assertEquals(Map.of(), again.get(BY_TYPE));
    }

    @Test
    void missingKeysAreAddedAndTheOwnersTextIsKept() throws IOException {
        write("# My own note.",
                "limits:",
                "  per-chunk: 12 # trimmed for this server",
                "old-setting: 3");

        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);

        assertEquals(12, config.get(LIMIT));
        String text = text();
        assertTrue(text.contains("# My own note."), text);
        assertTrue(text.contains("per-chunk: 12 # trimmed for this server"), text);
        assertTrue(text.contains("old-setting: 3"), "keys nobody declared stay: " + text);
        assertTrue(text.contains("# Shown after energy amounts.\n    unit: J"), text);
        assertTrue(this.logs.stream().anyMatch(record -> record.getMessage().contains("added 6 missing setting(s)")));
    }

    @Test
    void anUpToDateFileIsNotRewritten() throws IOException {
        ConfigFile.load(this.file, SPEC, this.logger);
        String generated = text();
        write(generated.replace("ratio: 0.5", "ratio:    0.25   # tuned"));
        String edited = text();

        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);

        assertEquals(0.25, config.get(RATIO));
        assertEquals(edited, text(), "nothing was missing, so the file keeps its exact formatting");
    }

    @Test
    void anInvalidValueUsesTheDefaultAndStaysInTheFile() throws IOException {
        ConfigFile.load(this.file, SPEC, this.logger);
        write(text().replace("per-chunk: 256", "per-chunk: lots").replace("ratio: 0.5", "ratio: 5").replace("mode: safe", "mode: turbo"));
        this.logs.clear();

        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);

        assertEquals(256, config.get(LIMIT));
        assertEquals(0.5, config.get(RATIO));
        assertEquals(Mode.SAFE, config.get(MODE));
        assertEquals(3, warnings());
        assertTrue(this.logs.stream().anyMatch(record -> record.getMessage().contains(
                "limits.per-chunk must be a whole number from 0 to 100000 but is 'lots'; using the default 256")));
        assertTrue(text().contains("per-chunk: lots"), "the owner's text is left for them to fix");
    }

    @Test
    void valuesOfEveryTypeAreRead() throws IOException {
        write("mode: Fast_Mode",
                "names: [x, 2, true]",
                "ratio: 1",
                "menus:",
                "  by-type:",
                "    example:generator: machine",
                "    other: 3",
                "resources:",
                "  energy:",
                "    unit: 1000");

        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);

        assertEquals(Mode.FAST_MODE, config.get(MODE));
        assertEquals(List.of("x", "2", "true"), config.get(NAMES));
        assertEquals(1.0, config.get(RATIO));
        assertEquals(Map.of("example:generator", "machine", "other", "3"), config.get(BY_TYPE));
        assertEquals("1000", config.get(UNIT));
        assertEquals(0, warnings());
    }

    @Test
    void aValueWhereASectionBelongsIsReportedAndKept() throws IOException {
        write("limits: 5");

        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);

        assertEquals(256, config.get(LIMIT));
        assertTrue(text().contains("limits: 5"), "not replaced by a section");
        assertTrue(this.logs.stream().anyMatch(record -> record.getMessage().contains("limits must be a section")));
    }

    @Test
    void aBrokenFileIsLeftAloneAndDefaultsAreUsed() throws IOException {
        write("limits:", "  per-chunk: [unclosed");

        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);

        assertEquals(256, config.get(LIMIT));
        assertTrue(text().contains("[unclosed"));
        assertTrue(this.logs.stream().anyMatch(record -> record.getLevel() == Level.SEVERE));
    }

    @Test
    void reloadChangesValuesButNotRestartOnlyOnes() throws IOException {
        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);
        write(text().replace("per-chunk: 256", "per-chunk: 64").replace("unit: J", "unit: FE").replace("bridge: true", "bridge: false"));

        ConfigLoadResult result = config.reload();

        assertEquals(List.of("limits.per-chunk"), result.changed());
        assertEquals(List.of("events.bridge", "resources.energy.unit"), result.restartRequired());
        assertEquals(64, config.get(LIMIT));
        assertEquals("J", config.get(UNIT));
        assertEquals(true, config.get(BRIDGE));
    }

    @Test
    void reloadOfABrokenFileKeepsTheOldValues() throws IOException {
        ConfigFile.load(this.file, SPEC, this.logger);
        write(text().replace("per-chunk: 256", "per-chunk: 64"));
        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);
        write("limits: [");

        ConfigLoadResult result = config.reload();

        assertNotNull(result.error());
        assertEquals(64, config.get(LIMIT));
    }

    @Test
    void reloadAddsKeysThatWentMissing() throws IOException {
        ConfigFile config = ConfigFile.load(this.file, SPEC, this.logger);
        write("limits:", "  per-chunk: 10");

        ConfigLoadResult result = config.reload();

        assertEquals(6, result.added().size());
        assertTrue(text().contains("unit: J"));
        assertEquals(10, config.get(LIMIT));
    }

    @Test
    void clampedNumbersUseTheNearestBound() throws IOException {
        ConfigKey<Integer> radius = ConfigKey.integer("radius", 2, 0, 8).clamped();
        ConfigKey<Double> speed = ConfigKey.decimal("speed", 0.5, 0, 2).clamped();
        ConfigKey<Long> energy = ConfigKey.whole("energy", 100, 0, 1_000).clamped();
        ConfigSpec spec = ConfigSpec.builder().key(radius).key(speed).key(energy).build();
        write("radius: 40", "speed: -3", "energy: lots");

        ConfigFile config = ConfigFile.load(this.file, spec, this.logger);

        assertEquals(8, config.get(radius));
        assertEquals(0.0, config.get(speed));
        assertEquals(100, config.get(energy), "not a number: the default, even when clamped");
        assertEquals(3, warnings());
        assertTrue(this.logs.stream().anyMatch(record -> record.getMessage().contains("radius must be a whole number from 0 to 8 but is '40'; using 8")));
        assertThrows(IllegalStateException.class, () -> ConfigKey.string("text", "a").clamped());
    }

    @Test
    void wholeListsHaveTheirSizeAndRange() throws IOException {
        ConfigKey<List<Long>> tiers = ConfigKey.wholes("tiers", List.of(1L, 2L, 3L, 4L), 4, 1, 100);
        ConfigKey<List<Long>> any = ConfigKey.wholes("any", List.of(), 0, 0, 10);
        ConfigSpec spec = ConfigSpec.builder().key(tiers).key(any).build();
        write("tiers: [5, 6, 7, 8]", "any: [1, 2, 3]");
        ConfigFile config = ConfigFile.load(this.file, spec, this.logger);
        assertEquals(List.of(5L, 6L, 7L, 8L), config.get(tiers));
        assertEquals(List.of(1L, 2L, 3L), config.get(any));

        write("tiers: [5, 6, 7]", "any: [1, 20]");
        config = ConfigFile.load(this.file, spec, this.logger);
        assertEquals(List.of(1L, 2L, 3L, 4L), config.get(tiers), "three numbers: the default");
        assertEquals(List.of(), config.get(any), "out of range: the default");
        assertTrue(this.logs.stream().anyMatch(record -> record.getMessage().contains("tiers must be 4 whole numbers, each from 1 to 100")));
    }

    @Test
    void defaultsNeedNoFile() {
        ConfigValues defaults = SPEC.defaults();
        assertEquals(256, defaults.get(LIMIT));
        assertThrows(IllegalArgumentException.class, () -> defaults.get(ConfigKey.bool("unknown", false)));
    }

    @Test
    void badSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ConfigSpec.builder().key(LIMIT).key(ConfigKey.integer("limits.per-chunk", 1, 0, 2)));
        assertThrows(IllegalArgumentException.class, () -> ConfigSpec.builder().key(ConfigKey.bool("a", true)).key(ConfigKey.bool("a.b", true)).build());
        assertThrows(IllegalArgumentException.class, () -> ConfigSpec.builder().section("a").key(ConfigKey.bool("a", true)).build());
        assertThrows(IllegalArgumentException.class, () -> ConfigKey.integer("x", 300, 0, 256));
        assertThrows(IllegalArgumentException.class, () -> ConfigKey.bool("a..b", true));
    }
}
