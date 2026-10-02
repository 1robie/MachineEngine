package fr.robie.machineengine.example;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.addon.AddonRegistration;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Example addon. Registers its content in {@code onLoad} (D13), installs its CraftEngine block pack,
 * and adds {@code /mexample place <x> <y> <z> [world]}, which places a generator with a consumer
 * north of it (the generator's front faces north).
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
    }

    @Override
    public void onEnable() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("mexample")
                        .requires(source -> source.getSender().hasPermission("machineengine.admin"))
                        .then(Commands.literal("place")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("world", StringArgumentType.word())
                                                                .executes(context -> {
                                                                    World world = Bukkit.getWorld(StringArgumentType.getString(context, "world"));
                                                                    if (world == null) return 0;
                                                                    int x = IntegerArgumentType.getInteger(context, "x");
                                                                    int y = IntegerArgumentType.getInteger(context, "y");
                                                                    int z = IntegerArgumentType.getInteger(context, "z");
                                                                    boolean placed = place(world, x, y, z, "generator")
                                                                            && place(world, x, y, z - 1, "consumer");
                                                                    context.getSource().getSender().sendMessage(placed
                                                                            ? "Placed a generator at " + x + " " + y + " " + z + " and a consumer north of it."
                                                                            : "Placement failed (are the example blocks loaded in CraftEngine?)");
                                                                    return placed ? Command.SINGLE_SUCCESS : 0;
                                                                }))))))
                        .build(),
                "MachineEngine example commands"));
    }

    private static boolean place(World world, int x, int y, int z, String name) {
        return CraftEngineBlocks.place(new Location(world, x, y, z), net.momirealms.craftengine.core.util.Key.of("example", name), false);
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
