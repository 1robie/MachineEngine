package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.core.definition.DefinitionCompiler;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads data-driven definitions from {@code plugins/MachineEngine/definitions/*.yml} (§29) into
 * format-neutral trees for the {@link DefinitionCompiler}. Read again on every recipe reload.
 */
public final class YamlDefinitions {
    static final String EXAMPLE = "definitions/example.yml";

    private final Plugin plugin;

    public YamlDefinitions(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    public @NotNull List<DefinitionFile> load() {
        File folder = new File(this.plugin.getDataFolder(), "definitions");
        if (!folder.exists()) this.plugin.saveResource(EXAMPLE, false);
        // Sub-folders too (e.g. definitions/mekanized/), sorted by path for a stable order.
        File[] files;
        try (var walk = Files.walk(folder.toPath())) {
            files = walk.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .map(Path::toFile).filter(File::isFile).toArray(File[]::new);
        } catch (IOException | UncheckedIOException e) {
            this.plugin.getLogger().warning("Could not list definitions: " + e.getMessage());
            return List.of();
        }
        Arrays.sort(files, Comparator.comparing(file -> folder.toPath().relativize(file.toPath()).toString().replace('\\', '/')));
        List<DefinitionFile> definitions = new ArrayList<>();
        for (File file : files) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                this.plugin.getLogger().warning("Skipped definitions/" + relative(folder, file) + ": " + e.getMessage());
                continue;
            }
            definitions.add(new DefinitionFile("definitions/" + relative(folder, file), toData(yaml)));
        }
        return definitions;
    }

    private static String relative(File folder, File file) {
        return folder.toPath().relativize(file.toPath()).toString().replace('\\', '/');
    }

    /** Vanilla items by id ({@code minecraft:iron_ingot}). */
    public static @NotNull DefinitionCompiler.ItemResolver vanillaItems() {
        return YamlDefinitions::item;
    }

    private static Result<ResourceVariant> item(Key id) {
        Material material = Material.matchMaterial(id.asString());
        if (material == null || !material.isItem() || material.isAir()) {
            return Result.err(MachineError.of(DiagnosticCodes.DEFINITION_SKIPPED,
                    "'" + id.asString() + "' is not a vanilla item"));
        }
        return Result.ok(PaperItems.toResource(new ItemStack(material)).variant());
    }

    static @NotNull DataMap toData(@NotNull ConfigurationSection section) {
        Map<String, DataValue> entries = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            DataValue value = toData(section.get(key));
            if (value != null) entries.put(key, value);
        }
        return DataMap.of(entries);
    }

    private static DataValue toData(Object value) {
        return switch (value) {
            case null -> null;
            case ConfigurationSection section -> toData(section);
            case Map<?, ?> map -> {
                Map<String, DataValue> entries = new LinkedHashMap<>();
                map.forEach((key, element) -> {
                    DataValue converted = toData(element);
                    if (converted != null) entries.put(String.valueOf(key), converted);
                });
                yield DataMap.of(entries);
            }
            case List<?> list -> DataValue.list(list.stream().map(YamlDefinitions::toData).filter(Objects::nonNull).toList());
            case Boolean bool -> DataValue.of(bool);
            case Double number -> DataValue.of(number);
            case Float number -> DataValue.of((double) number);
            case Number number -> DataValue.of(number.longValue());
            default -> DataValue.of(String.valueOf(value));
        };
    }
}
