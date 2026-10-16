package fr.robie.machineengine.paper.fluid.pack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidTexture;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import fr.robie.machineengine.paper.fluid.client.KeyPalette;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Writes the client side of world fluids into MachineEngine's CraftEngine pack (docs/FLUID_TYPES_PLAN.md
 * §7): an overlay for the server's version with the water sprites as texture grids ({@link TextureGrid})
 * and the version's water shader with the fluids added ({@link ShaderPatch}). Everything is made from the
 * official client jar of that version ({@link VanillaClient}); a client of another version gets no
 * overlay and sees the plain key colors.
 */
public final class FluidPackBuilder {
    /** The overlay folder in {@code resourcepack/}. */
    public static final String OVERLAY = "machineengine_world_fluids";
    private static final int STILL = 16;
    private static final int FLOW = 32;

    /** {@code world-fluids.*} as the builder needs them. */
    public record Settings(int neutral, boolean uniform, boolean sharp, int maxColumns) {
    }

    /** What was written. */
    public record Result(@NotNull String format, int looks, int textures, int columns, @NotNull String shader, @NotNull List<String> notes) {
    }

    private FluidPackBuilder() {
    }

    /**
     * @param look       the look in effect for a fluid
     * @param resources  {@code plugins/CraftEngine/resources} (fluid textures from packs are read there)
     * @param pack       MachineEngine's CraftEngine pack folder (the overlay is written into its {@code resourcepack/})
     */
    public static @NotNull Result build(@NotNull VanillaClient client, @NotNull FluidKeys keys, @NotNull Function<FluidType, FluidLook> look,
                                        @NotNull Settings settings, @NotNull Path resources, @NotNull Path pack)
            throws IOException, ShaderPatch.MismatchException {
        List<String> notes = new ArrayList<>();
        int[] format = packFormat(client);
        String shader = client.read("assets/minecraft/shaders/core/terrain.vsh") != null ? "terrain" : "rendertype_translucent";
        String vanillaVertex = client.text("assets/minecraft/shaders/core/" + shader + ".vsh");
        String vanillaFragment = client.text("assets/minecraft/shaders/core/" + shader + ".fsh");
        if (vanillaVertex == null || vanillaFragment == null) throw new IOException("the client jar has no " + shader + " shader");
        Path overlay = pack.resolve("resourcepack").resolve(OVERLAY);
        deleteTree(overlay);
        if (keys.keyCount() == 0) {
            writeMeta(pack, format, false);
            return new Result(formatText(format), 0, 0, 0, shader, List.of("no placeable fluid: nothing to draw"));
        }

        // One look per key: the look of the first fluid that has it (fluids sharing a key look the same).
        FluidLook[] looks = new FluidLook[keys.keyCount()];
        int[] colors = new int[keys.keyCount()];
        for (int number = 1; number <= keys.fluidCount(); number++) {
            if (!keys.hasKey(number)) continue;
            int index = keys.keyIndex(number);
            FluidType fluid = keys.fluid(number);
            if (looks[index] != null || fluid == null) continue;
            looks[index] = FluidKeys.resolved(look.apply(fluid), fluid.color());
            colors[index] = fluid.color() == null ? 0xFFFFFF : fluid.color();
        }

        // Distinct textures get a tile each (tile 0 is water); untextured looks use water's tile, tinted.
        Map<FluidTexture, Integer> stillTiles = new IdentityHashMap<>();
        Map<FluidTexture, Integer> flowTiles = new IdentityHashMap<>();
        for (FluidLook each : looks) {
            FluidTexture still = each.still() != null ? each.still() : each.flow();
            FluidTexture flow = each.flow() != null ? each.flow() : each.still();
            if (still != null) stillTiles.putIfAbsent(still, stillTiles.size() + 1);
            if (flow != null) flowTiles.putIfAbsent(flow, flowTiles.size() + 1);
        }
        int maxColumns = Math.min(settings.maxColumns(), TextureGrid.MAX_FRAME / FLOW);
        int columns = TextureGrid.columns(Math.max(stillTiles.size(), flowTiles.size()) + 1);
        if (columns > maxColumns) {
            notes.add("only " + (maxColumns * maxColumns - 1) + " textures fit world-fluids.texture-grid " + maxColumns
                    + ": the other fluids are water tinted with their color");
            columns = maxColumns;
        }
        int capacity = columns * columns - 1;

        BufferedImage waterStill = client.texture("block/water_still");
        BufferedImage waterFlow = client.texture("block/water_flow");
        List<BufferedImage> stillFrames = frames(waterStill);
        List<BufferedImage> flowFrames = frames(waterFlow);
        int stillTime = client.frameTime("block/water_still");
        int flowTime = client.frameTime("block/water_flow");
        Context stillContext = new Context(client, resources, false);
        Context flowContext = new Context(client, resources, true);
        List<List<BufferedImage>> stillGrid = tiles(stillFrames, stillTiles, looks, true, stillContext, STILL, stillTime, capacity, notes);
        List<List<BufferedImage>> flowGrid = tiles(flowFrames, flowTiles, looks, false, flowContext, FLOW, flowTime, capacity, notes);

        int n = keys.keyCount() + 1;
        int[] still = new int[n];
        int[] flow = new int[n];
        int[] tint = new int[n];
        double[] alpha = new double[n];
        double[] glow = new double[n];
        tint[0] = 0xFFFFFF;
        alpha[0] = averageAlpha(waterStill);
        for (int i = 0; i < looks.length; i++) {
            FluidLook each = looks[i];
            FluidTexture stillTexture = each.still() != null ? each.still() : each.flow();
            FluidTexture flowTexture = each.flow() != null ? each.flow() : each.still();
            still[i + 1] = tile(stillTiles, stillTexture, capacity);
            flow[i + 1] = tile(flowTiles, flowTexture, capacity);
            // A look whose texture didn't fit is water tinted with its color.
            boolean textured = still[i + 1] != 0 || flow[i + 1] != 0;
            tint[i + 1] = each.tint() != null ? each.tint() : 0xFFFFFF;
            if (!textured && each.textured()) tint[i + 1] = colors[i];
            alpha[i + 1] = each.opacity();
            glow[i + 1] = each.glow();
        }
        List<Integer> keyColors = new ArrayList<>();
        for (int i = 0; i < keys.keyCount(); i++) keyColors.add(keys.keyColor(i));
        List<Integer> backgrounds = new ArrayList<>();
        if (settings.uniform()) {
            backgrounds.add(settings.neutral());
        } else {
            backgrounds.addAll(client.waterColors());
            if (!backgrounds.contains(settings.neutral())) backgrounds.add(settings.neutral());
        }
        ShaderPatch.Constants constants = new ShaderPatch.Constants(keyColors, settings.neutral(), backgrounds,
                KeyPalette.shareCut(keys.keyCount()), columns, still, flow, tint, alpha, glow, settings.sharp());
        String vertex = ShaderPatch.vertex(vanillaVertex, constants);
        String fragment = ShaderPatch.fragment(vanillaFragment, constants);

        Path textures = overlay.resolve("assets/minecraft/textures/block");
        Files.createDirectories(textures);
        writeGrid(textures, "water_still", TextureGrid.build(stillGrid, STILL, columns, stillFrames.size(), false), columns * STILL, stillTime);
        writeGrid(textures, "water_flow", TextureGrid.build(flowGrid, FLOW, columns, flowFrames.size(), true), columns * FLOW, flowTime);
        Path shaders = overlay.resolve("assets/minecraft/shaders/core");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve(shader + ".vsh"), vertex, StandardCharsets.UTF_8);
        Files.writeString(shaders.resolve(shader + ".fsh"), fragment, StandardCharsets.UTF_8);
        writeMeta(pack, format, true);
        return new Result(formatText(format), keys.keyCount(), Math.max(stillTiles.size(), flowTiles.size()), columns, shader, notes);
    }

    /** Removes the overlay ({@code world-fluids.shader: false}): players see the plain key colors. */
    public static void remove(@NotNull Path pack) throws IOException {
        deleteTree(pack.resolve("resourcepack").resolve(OVERLAY));
        Path meta = pack.resolve("resourcepack/pack.mcmeta");
        if (Files.isRegularFile(meta) && Files.readString(meta).contains(OVERLAY)) Files.delete(meta);
    }

    private static int tile(Map<FluidTexture, Integer> tiles, @Nullable FluidTexture texture, int capacity) {
        if (texture == null) return 0;
        int tile = tiles.getOrDefault(texture, 0);
        return tile <= capacity ? tile : 0;
    }

    private static List<List<BufferedImage>> tiles(List<BufferedImage> water, Map<FluidTexture, Integer> tiles, FluidLook[] looks,
                                                   boolean stillKind, Context context, int size, int vanillaTime, int capacity,
                                                   List<String> notes) {
        List<List<BufferedImage>> grid = new ArrayList<>();
        grid.add(water);
        List<Map.Entry<FluidTexture, Integer>> ordered = new ArrayList<>(tiles.entrySet());
        ordered.sort(Comparator.comparingInt(Map.Entry::getValue));
        for (Map.Entry<FluidTexture, Integer> entry : ordered) {
            if (entry.getValue() > capacity) break;
            int frameTime = 2;
            for (FluidLook each : looks) {
                FluidTexture used = stillKind ? (each.still() != null ? each.still() : each.flow()) : (each.flow() != null ? each.flow() : each.still());
                if (used == entry.getKey()) {
                    frameTime = each.frameTime();
                    break;
                }
            }
            List<BufferedImage> frames;
            try {
                frames = entry.getKey().frames(size, context);
                if (frames.isEmpty()) throw new IOException("no frames");
            } catch (IOException | RuntimeException e) {
                notes.add("a " + (stillKind ? "still" : "flowing") + " texture failed (" + e.getMessage() + "): water's is used");
                frames = water;
            }
            grid.add(timed(frames, water.size(), vanillaTime, frameTime));
        }
        return grid;
    }

    /** {@code count} frames shown every {@code vanillaTime} ticks, playing a texture made for {@code ownTime}. */
    static List<BufferedImage> timed(List<BufferedImage> frames, int count, int vanillaTime, int ownTime) {
        List<BufferedImage> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(frames.get((int) ((long) i * vanillaTime / Math.max(1, ownTime) % frames.size())));
        return out;
    }

    /** The square frames of a vertical strip. */
    static List<BufferedImage> frames(BufferedImage strip) {
        int side = strip.getWidth();
        List<BufferedImage> frames = new ArrayList<>();
        for (int y = 0; y + side <= strip.getHeight(); y += side) frames.add(strip.getSubimage(0, y, side, side));
        if (frames.isEmpty()) frames.add(strip);
        return frames;
    }

    private static double averageAlpha(BufferedImage image) {
        long sum = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) sum += image.getRGB(x, y) >>> 24;
        return sum / (255.0 * image.getWidth() * image.getHeight());
    }

    private static void writeGrid(Path folder, String name, BufferedImage grid, int side, int frameTime) throws IOException {
        ImageIO.write(grid, "png", folder.resolve(name + ".png").toFile());
        JsonObject animation = new JsonObject();
        animation.addProperty("frametime", frameTime);
        animation.addProperty("width", side);
        animation.addProperty("height", side);
        JsonObject meta = new JsonObject();
        meta.add("animation", animation);
        Files.writeString(folder.resolve(name + ".png.mcmeta"), new GsonBuilder().setPrettyPrinting().create().toJson(meta), StandardCharsets.UTF_8);
    }

    /** {@code resourcepack/pack.mcmeta}: the overlay for exactly this version (CraftEngine merges it into the pack). */
    private static void writeMeta(Path pack, int[] format, boolean overlay) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject info = new JsonObject();
        info.addProperty("description", "MachineEngine");
        info.addProperty("pack_format", format[0]);
        info.add("min_format", version(format));
        info.add("max_format", version(format));
        root.add("pack", info);
        if (overlay) {
            JsonObject entry = new JsonObject();
            entry.addProperty("directory", OVERLAY);
            JsonArray formats = new JsonArray();
            formats.add(format[0]);
            formats.add(format[0]);
            entry.add("formats", formats);
            entry.add("min_format", version(format));
            entry.add("max_format", version(format));
            JsonArray entries = new JsonArray();
            entries.add(entry);
            JsonObject overlays = new JsonObject();
            overlays.add("entries", entries);
            root.add("overlays", overlays);
        }
        Path meta = pack.resolve("resourcepack/pack.mcmeta");
        Files.createDirectories(meta.getParent());
        Files.writeString(meta, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
    }

    private static JsonArray version(int[] format) {
        JsonArray array = new JsonArray();
        array.add(format[0]);
        array.add(format[1]);
        return array;
    }

    /** The resource pack format of the client ({@code version.json}): {major, minor}. */
    static int[] packFormat(VanillaClient client) throws IOException {
        String text = client.text("version.json");
        if (text == null) throw new IOException("the client jar has no version.json");
        JsonObject pack = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("pack_version");
        if (pack.has("resource")) return new int[]{pack.get("resource").getAsInt(), 0};
        return new int[]{pack.get("resource_major").getAsInt(), pack.get("resource_minor").getAsInt()};
    }

    private static String formatText(int[] format) {
        return format[1] == 0 ? String.valueOf(format[0]) : format[0] + "." + format[1];
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** What a {@link FluidTexture} reads while the pack is built. */
    private record Context(VanillaClient client, Path resources, boolean flowing) implements FluidTexture.Context {
        @Override
        public @NotNull List<BufferedImage> vanilla(@NotNull String path, int size) throws IOException {
            return scaled(frames(this.client.texture(path)), size);
        }

        @Override
        public @NotNull List<BufferedImage> pack(@NotNull Key texture, int size) throws IOException {
            String relative = "resourcepack/assets/" + texture.namespace() + "/textures/" + texture.value() + ".png";
            if (Files.isDirectory(this.resources)) {
                try (Stream<Path> packs = Files.list(this.resources)) {
                    for (Path pack : packs.sorted().toList()) {
                        Path file = pack.resolve(relative);
                        if (!Files.isRegularFile(file)) continue;
                        BufferedImage image = ImageIO.read(file.toFile());
                        if (image == null) throw new IOException("unreadable texture " + file);
                        return scaled(frames(image), size);
                    }
                }
            }
            throw new IOException("no texture " + texture.asString() + " in the CraftEngine packs");
        }

        private static List<BufferedImage> scaled(List<BufferedImage> frames, int size) {
            List<BufferedImage> out = new ArrayList<>(frames.size());
            for (BufferedImage frame : frames) out.add(TextureGrid.fit(frame, size));
            return out;
        }
    }
}
