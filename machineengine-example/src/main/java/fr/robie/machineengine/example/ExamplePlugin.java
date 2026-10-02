package fr.robie.machineengine.example;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.addon.AddonRegistration;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Example addon. Registers its content in {@code onLoad} (D13), installs its CraftEngine block pack,
 * and adds the {@code /mexample} test commands ({@link ExampleCommands}).
 */
@SuppressWarnings("UnstableApiUsage")
public final class ExamplePlugin extends JavaPlugin {
    private static final List<String> PACK_FILES = List.of("pack.yml", "configuration/machines.yml");

    @Override
    public void onLoad() {
        MachineEngine.register(AddonRegistration.builder(getName(), getPluginMeta().getVersion())
                .requiresApi("[0.1,1.0)")
                .register(ExampleContent::register)
                .build());
        installCraftEnginePack();
        installMenus();
    }

    /**
     * Installs the example's machine menus into MachineEngine's menus folder. Menu files are named
     * {@code <namespace>_<value>.yml}, which MachineEngine maps to that machine type. Existing files
     * are kept, so server owners can edit them.
     */
    private void installMenus() {
        Path menus = getDataFolder().toPath().getParent().resolve("MachineEngine/menus");
        for (String file : List.of("example_crate.yml", "example_consumer.yml")) {
            Path destination = menus.resolve(file);
            if (Files.exists(destination)) continue;
            try (InputStream in = getResource("machineengine-menus/" + file)) {
                if (in == null) throw new IOException("missing resource " + file);
                Files.createDirectories(menus);
                Files.copy(in, destination);
            } catch (IOException e) {
                getLogger().warning("Could not install the menu " + file + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new ExampleBukkitListener(getLogger()), this);
        ExampleCommands commands = new ExampleCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(commands.build(), "MachineEngine example commands"));
    }

    /** Copies the CraftEngine pack defining the example blocks, before CraftEngine loads its packs. */
    private void installCraftEnginePack() {
        Path target = getDataFolder().toPath().getParent().resolve("CraftEngine/resources/machineengine_example");
        for (String file : PACK_FILES) {
            Path destination = target.resolve(file);
            try (InputStream in = getResource("craftengine-pack/" + file)) {
                if (in == null) throw new IOException("missing resource " + file);
                Files.createDirectories(destination.getParent());
                Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                getLogger().warning("Could not install the CraftEngine pack file " + file + ": " + e.getMessage());
            }
        }
    }
}
