package fr.robie.machineengine.paper.fluid;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.paper.WorldFluids;
import fr.robie.machineengine.api.paper.event.WorldFluidPlaceEvent;
import fr.robie.machineengine.api.paper.event.WorldFluidRemoveEvent;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.fluid.FluidEffect;
import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidMixing;
import fr.robie.machineengine.api.resource.fluid.FluidPhysics;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.Viscosity;
import fr.robie.machineengine.core.resource.fluid.FluidOverrides;
import fr.robie.machineengine.paper.YamlDefinitions;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code plugins/MachineEngine/fluids.yml}: the server owner's overrides of each fluid's settings
 * (docs/FLUID_TYPES_PLAN.md §3). Written on first start with every placeable fluid's values as
 * commented examples; read at enable and on {@code /machineengine reload fluids}. Installs the
 * {@link WorldFluids} answers for the values in effect.
 */
public final class FluidSettingsFile {
    public static final String FILE = "fluids.yml";

    private final Path file;
    private final MachineEngine engine;
    private volatile FluidOverrides current = FluidOverrides.NONE;

    private FluidSettingsFile(Path file, MachineEngine engine) {
        this.file = file;
        this.engine = engine;
    }

    /** Reads (or writes) the file and installs the {@link WorldFluids} answers. */
    public static @NotNull FluidSettingsFile install(@NotNull Path dataFolder, @NotNull MachineEngine engine,
                                                     @NotNull List<Diagnostic> report) {
        FluidSettingsFile settings = new FluidSettingsFile(dataFolder.resolve(FILE), engine);
        report.addAll(settings.reload().diagnostics());
        WorldFluids.setAccess(settings.access(WorldFluids.vanillaOnly()));
        return settings;
    }

    /** Answers {@link WorldFluids} with the running world fluids' blocks and this file's settings. */
    public void attach(@NotNull WorldFluids.Access blocks) {
        WorldFluids.setAccess(access(blocks));
    }

    /** Stops answering from this file (MachineEngine disables). */
    public static void uninstall() {
        WorldFluids.setAccess(null);
    }

    public @NotNull FluidOverrides current() {
        return this.current;
    }

    /**
     * Reads the file again. Physics, mixing and effects apply at once; looks after the pack is rebuilt;
     * fog after players rejoin. If the file can't be read, the previous overrides stay.
     */
    public @NotNull ValidationReport reload() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            if (Files.notExists(this.file)) {
                Files.createDirectories(this.file.getParent());
                Files.writeString(this.file, template(placeableFluids()), StandardCharsets.UTF_8);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(this.file, StandardCharsets.UTF_8));
            DataMap root = YamlDefinitions.toData(yaml);
            this.current = FluidOverrides.parse(root, this.engine.registries(), diagnostics::add);
        } catch (IOException | InvalidConfigurationException e) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.DEFINITION_SKIPPED,
                    FILE + " could not be read; the previous overrides stay: " + e.getMessage()).location(FILE).build());
        }
        return ValidationReport.of(diagnostics);
    }

    private List<FluidType> placeableFluids() {
        List<FluidType> fluids = new ArrayList<>();
        for (ResourceType type : this.engine.registries().registry(Registries.RESOURCE_TYPE).values()) {
            if (type instanceof FluidType fluid && fluid.placeable()) fluids.add(fluid);
        }
        return fluids;
    }

    /** The answers of {@link WorldFluids}: blocks from {@code blocks}, settings with this file's overrides. */
    WorldFluids.Access access(WorldFluids.Access blocks) {
        return new WorldFluids.Access() {
            @Override
            public @Nullable ResourceType at(@NotNull Block block) {
                return blocks.at(block);
            }

            @Override
            public WorldFluids.@NotNull Placement canPlace(@NotNull Block block, @NotNull FluidType fluid) {
                return blocks.canPlace(block, fluid);
            }

            @Override
            public boolean place(@NotNull Block block, @NotNull FluidType fluid, WorldFluidPlaceEvent.@NotNull Cause cause) {
                return blocks.place(block, fluid, cause);
            }

            @Override
            public @Nullable ResourceType take(@NotNull Block block, WorldFluidRemoveEvent.@NotNull Cause cause) {
                return blocks.take(block, cause);
            }

            @Override
            public @NotNull FluidPhysics physics(@NotNull FluidType fluid) {
                return FluidSettingsFile.this.current.physics(fluid);
            }

            @Override
            public @NotNull FluidLook look(@NotNull FluidType fluid) {
                return FluidSettingsFile.this.current.look(fluid);
            }

            @Override
            public @NotNull FluidMixing mixing(@NotNull FluidType fluid) {
                return FluidSettingsFile.this.current.mixing(fluid);
            }

            @Override
            public @NotNull List<FluidEffect> effects(@NotNull FluidType fluid) {
                return FluidSettingsFile.this.current.effects(fluid);
            }
        };
    }

    /** The file written on first start: how it works, and every placeable fluid's values to copy. */
    static @NotNull String template(@NotNull List<FluidType> fluids) {
        StringBuilder out = new StringBuilder();
        out.append("""
                # Overrides of each fluid's settings, on top of its definition (code or definitions YAML).
                # Write only the fields to change; the others keep the definition's values.
                #
                # /machineengine reload fluids
                #   physics, mixing, effects  apply at once
                #   look (textures, tint, opacity, glow, frame-time)  after the resource pack is rebuilt
                #   look.fog  after players rejoin
                # world and bucket can't be changed here: set them in the definition (restart).
                #
                # Fields:
                #   look: { still, flow, texture, tint: "#RRGGBB", opacity: 0-1, glow: 0-1, fog: "#RRGGBB", frame-time }
                #     texture: a resource pack key (mymod:block/oil_still) or { source: recolor, pattern: lava, from, to }
                #   physics:
                #     viscosity: { speed, gravity, jump, swim: 0-1, damping: 0-0.9, entity-drag: 0-0.9 }
                #     spread: { ticks: 5+, range: 1-7 }
                #     infinite-source: true | false
                #     mixed-source: none | water
                #   mixing: [ { with: water | any | "#<tag>" | <fluid key>, result: stop | flow | block | fluid, ... } ]
                #   effects: [ { type: potion | damage | fire | freeze, ... } ]
                #
                """);
        if (fluids.isEmpty()) {
            out.append("# No placeable fluid is registered yet.\n");
        } else {
            out.append("# The placeable fluids and their current values (copy a block under \"fluids:\" and remove the #):\n");
            for (FluidType fluid : fluids) {
                FluidLook look = fluid.look();
                Viscosity v = fluid.physics().viscosity();
                out.append("#  ").append(fluid.key().asString()).append(":\n");
                out.append("#    look: { opacity: ").append(number(look.opacity())).append(", glow: ").append(number(look.glow()))
                        .append(", tint: \"").append(color(look.tintFor(fluid.color()))).append("\", fog: \"")
                        .append(color(look.fogFor(fluid.color()))).append("\" }\n");
                out.append("#    physics:\n");
                out.append("#      viscosity: { speed: ").append(number(v.speed())).append(", gravity: ").append(number(v.gravity()))
                        .append(", jump: ").append(number(v.jump())).append(", swim: ").append(number(v.swim()))
                        .append(", damping: ").append(number(v.damping())).append(", entity-drag: ").append(number(v.entityDrag())).append(" }\n");
                out.append("#      spread: { ticks: ").append(fluid.physics().spread().ticks()).append(", range: ")
                        .append(fluid.physics().spread().range()).append(" }\n");
                out.append("#      infinite-source: ").append(fluid.physics().infiniteSource()).append('\n');
                out.append("#      mixed-source: ").append(fluid.physics().mixedSource().name().toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        out.append("\nfluids: {}\n");
        return out.toString();
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String color(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
