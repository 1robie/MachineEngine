package fr.robie.machineengine.paper.fluid.pack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.robie.machineengine.api.resource.fluid.FluidItems;
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

    /**
     * {@code world-fluids.*} as the builder needs them.
     *
     * @param grid       fluids with their own textures (a texture grid replaces the water textures), else
     *                    tint only (the water texture in each fluid's color; nothing to see for players without the shader)
     * @param waterModels with the grid, redirect the packs' models that draw vanilla water to an untouched copy
     * @param debug       {@code debug-colors}: water faces show how their color was read
     */
    public record Settings(int neutral, boolean uniform, boolean sharp, int maxColumns, boolean grid, boolean waterModels, boolean debug) {
        public Settings(int neutral, boolean uniform, boolean sharp, int maxColumns, boolean grid, boolean waterModels) {
            this(neutral, uniform, sharp, maxColumns, grid, waterModels, false);
        }
    }

    /** What was written. */
    public record Result(@NotNull String format, int looks, int textures, int columns, @NotNull String shader, @NotNull String sodium,
                         int waterModels, @NotNull List<String> notes) {
    }

    private FluidPackBuilder() {
    }

    /**
     * @param look       the look in effect for a fluid
     * @param resources  {@code plugins/CraftEngine/resources} (fluid textures from packs are read there)
     * @param pack       MachineEngine's CraftEngine pack folder (the overlay is written into its {@code resourcepack/})
     */
    public static @NotNull Result build(@NotNull VanillaClient client, @NotNull FluidKeys keys, @NotNull Function<FluidType, FluidLook> look,
                                        @NotNull Settings settings, @NotNull Path resources, @NotNull Path pack,
                                        @Nullable SodiumShaders sodium)
            throws IOException, ShaderPatch.MismatchException {
        List<String> notes = new ArrayList<>();
        int[] format = packFormat(client);
        String shader = client.read("assets/minecraft/shaders/core/terrain.vsh") != null ? "terrain" : "rendertype_translucent";
        String vanillaVertex = client.text("assets/minecraft/shaders/core/" + shader + ".vsh");
        String vanillaFragment = client.text("assets/minecraft/shaders/core/" + shader + ".fsh");
        if (vanillaVertex == null || vanillaFragment == null) throw new IOException("the client jar has no " + shader + " shader");
        Path finished = pack.resolve("resourcepack").resolve(OVERLAY);
        if (keys.keyCount() == 0) {
            deleteTree(finished);
            writeMeta(pack, format, false);
            return new Result(formatText(format), 0, 0, 0, shader, "no Sodium shader", 0, List.of("no placeable fluid: nothing to draw"));
        }

        // Built aside and swapped in whole once checked: a failed build leaves the previous pack as it was
        // (a half-written overlay could hold a shader importing a file not written yet, and the game drops
        // the whole pack on that).
        Path overlay = pack.resolve(".world-fluids-building");
        deleteTree(overlay);
        try {
            return write(client, keys, look, settings, resources, pack, sodium, overlay, finished, format, shader, vanillaVertex, vanillaFragment, notes);
        } finally {
            deleteTree(overlay);
        }
    }

    private static Result write(VanillaClient client, FluidKeys keys, Function<FluidType, FluidLook> look, Settings settings, Path resources,
                                Path pack, @Nullable SodiumShaders sodium, Path overlay, Path finished, int[] format, String shader,
                                String vanillaVertex, String vanillaFragment, List<String> notes) throws IOException, ShaderPatch.MismatchException {
        // One look per key: the look of the first fluid that has it (fluids sharing a key look the same).
        FluidLook[] looks = new FluidLook[keys.keyCount()];
        Integer[] ownTints = new Integer[keys.keyCount()];
        int[] colors = new int[keys.keyCount()];
        for (int number = 1; number <= keys.fluidCount(); number++) {
            if (!keys.hasKey(number)) continue;
            int index = keys.keyIndex(number);
            FluidType fluid = keys.fluid(number);
            if (looks[index] != null || fluid == null) continue;
            FluidLook own = look.apply(fluid);
            looks[index] = FluidKeys.resolved(own, fluid.color());
            ownTints[index] = own.tint();
            colors[index] = fluid.color() == null ? 0xFFFFFF : fluid.color();
        }

        // Distinct textures get a tile each (tile 0 is water); untextured looks use water's tile, tinted.
        // Tint only: no tiles, every look is water's texture in its color.
        Map<FluidTexture, Integer> stillTiles = new IdentityHashMap<>();
        Map<FluidTexture, Integer> flowTiles = new IdentityHashMap<>();
        for (FluidLook each : settings.grid() ? looks : new FluidLook[0]) {
            FluidTexture still = each.still() != null ? each.still() : each.flow();
            FluidTexture flow = each.flow() != null ? each.flow() : each.still();
            if (still != null) stillTiles.putIfAbsent(still, stillTiles.size() + 1);
            if (flow != null) flowTiles.putIfAbsent(flow, flowTiles.size() + 1);
        }
        int maxColumns = Math.min(settings.maxColumns(), TextureGrid.MAX_FRAME / FLOW);
        int columns = settings.grid() ? TextureGrid.columns(Math.max(stillTiles.size(), flowTiles.size()) + 1) : 0;
        if (columns > maxColumns) {
            notes.add("only " + (maxColumns * maxColumns - 1) + " textures fit world-fluids.texture-grid " + maxColumns
                    + ": the other fluids are water tinted with their color");
            columns = maxColumns;
        }
        int capacity = Math.max(0, columns * columns - 1);

        BufferedImage waterStill = client.texture("block/water_still");
        BufferedImage waterFlow = client.texture("block/water_flow");
        List<BufferedImage> stillFrames = frames(waterStill);
        List<BufferedImage> flowFrames = frames(waterFlow);
        int stillTime = client.frameTime("block/water_still");
        int flowTime = client.frameTime("block/water_flow");
        Context stillContext = new Context(client, resources, false);
        Context flowContext = new Context(client, resources, true);
        Animation stillGrid = settings.grid() ? animation(stillFrames, stillTime, stillTiles, looks, true, stillContext, STILL, columns, capacity, notes) : null;
        Animation flowGrid = settings.grid() ? animation(flowFrames, flowTime, flowTiles, looks, false, flowContext, FLOW, columns, capacity, notes) : null;

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
            if (still[i + 1] == 0 || flow[i + 1] == 0) {
                // Only one of its two textures fits: the look is water in its color, not half textured.
                still[i + 1] = 0;
                flow[i + 1] = 0;
            }
            // A look whose texture didn't fit is water tinted with its color.
            boolean textured = still[i + 1] != 0 || flow[i + 1] != 0;
            tint[i + 1] = each.tint() != null ? each.tint() : 0xFFFFFF;
            // Without its texture (tint only, or no room in the grid) a textured look is water in its own tint, else its color.
            if (!textured && each.textured()) tint[i + 1] = ownTints[i] != null ? ownTints[i] : colors[i];
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
                KeyPalette.shareCut(keys.keyCount()), columns, still, flow, tint, alpha, glow, settings.sharp(), settings.grid(), settings.debug());
        String vertex = ShaderPatch.vertex(vanillaVertex, constants);
        String fragment = ShaderPatch.fragment(vanillaFragment, constants);

        Path textures = overlay.resolve("assets/minecraft/textures/block");
        Files.createDirectories(textures);
        if (settings.grid()) {
            writeGrid(textures, "water_still", TextureGrid.build(stillGrid.tiles(), STILL, columns, stillGrid.frames(), false), columns * STILL, stillGrid.frameTime());
            writeGrid(textures, "water_flow", TextureGrid.build(flowGrid.tiles(), FLOW, columns, flowGrid.frames(), true), columns * FLOW, flowGrid.frameTime());
        } else {
            writeMarked(client, textures, "water_still");
            writeMarked(client, textures, "water_flow");
        }
        Path shaders = overlay.resolve("assets/minecraft/shaders/core");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve(shader + ".vsh"), vertex, StandardCharsets.UTF_8);
        Files.writeString(shaders.resolve(shader + ".fsh"), fragment, StandardCharsets.UTF_8);
        String sodiumNote = "no Sodium shader";
        if (sodium != null) {
            // The same fluids for players using Sodium (0.9+), which draws terrain with its own shader.
            Path sodiumShaders = overlay.resolve("assets/sodium/shaders/blocks");
            Files.createDirectories(sodiumShaders);
            Files.writeString(sodiumShaders.resolve("block_layer_opaque.vsh"),
                    ShaderPatch.vertex(sodium.vertex(), constants, ShaderPatch.Dialect.SODIUM), StandardCharsets.UTF_8);
            Files.writeString(sodiumShaders.resolve("block_layer_opaque.fsh"),
                    ShaderPatch.fragment(sodium.fragment(), constants, ShaderPatch.Dialect.SODIUM), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> include : sodium.includes().entrySet()) {
                Path file = overlay.resolve(include.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, include.getValue(), StandardCharsets.UTF_8);
            }
            sodiumNote = "Sodium " + sodium.version();
        }
        int redirected = settings.grid() && settings.waterModels() ? plainWater(client, resources, overlay) : 0;
        checkImports(client, overlay);
        deleteTree(finished);
        Files.createDirectories(finished.getParent());
        Files.move(overlay, finished);
        writeMeta(pack, format, true);
        return new Result(formatText(format), keys.keyCount(), Math.max(stillTiles.size(), flowTiles.size()), columns, shader, sodiumNote, redirected, notes);
    }

    /**
     * Tint only: the vanilla water texture with the marker alpha ({@link ShaderPatch#TINT_MARKER} for vanilla's
     * 180), so the shader knows water; it looks the same to everyone.
     */
    private static void writeMarked(VanillaClient client, Path folder, String name) throws IOException {
        BufferedImage image = client.texture("block/" + name);
        BufferedImage marked = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                marked.setRGB(x, y, (ShaderPatch.TINT_MARKER << 24) | (argb & 0xFFFFFF));
            }
        }
        ImageIO.write(marked, "png", folder.resolve(name + ".png").toFile());
        byte[] meta = client.read("assets/minecraft/textures/block/" + name + ".png.mcmeta");
        if (meta != null) Files.write(folder.resolve(name + ".png.mcmeta"), meta);
    }

    private static final java.util.regex.Pattern WATER_TEXTURE =
            java.util.regex.Pattern.compile("\"(?:minecraft:)?block/water_(still|flow)\"");

    /**
     * The overlay replaces {@code minecraft:block/water_still} and {@code water_flow} with the texture grid,
     * so whatever else draws water with them (menu fluid bars, particles) would show the grid. The overlay
     * therefore also holds the vanilla water as it is, at {@code machineengine:block/water_still} and
     * {@code water_flow}, and a copy of every model of the CraftEngine packs that uses the vanilla ones,
     * pointed at these. Overlay files replace the pack's at the same path, and only while the grid is there.
     *
     * @return how many models were redirected
     */
    static int plainWater(VanillaClient client, Path resources, Path overlay) throws IOException {
        Path folder = overlay.resolve("assets/machineengine/textures/block");
        Files.createDirectories(folder);
        for (String name : List.of("water_still", "water_flow")) {
            byte[] png = client.read("assets/minecraft/textures/block/" + name + ".png");
            byte[] meta = client.read("assets/minecraft/textures/block/" + name + ".png.mcmeta");
            if (png == null) throw new IOException("the client jar has no block/" + name);
            Files.write(folder.resolve(name + ".png"), png);
            if (meta != null) Files.write(folder.resolve(name + ".png.mcmeta"), meta);
        }
        if (!Files.isDirectory(resources)) return 0;
        int count = 0;
        try (Stream<Path> packs = Files.list(resources)) {
            for (Path pack : packs.sorted().toList()) {
                Path assets = pack.resolve("resourcepack/assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> files = Files.walk(assets)) {
                    for (Path model : files.filter(file -> file.toString().endsWith(".json")).toList()) {
                        Path relative = assets.relativize(model);
                        if (relative.getNameCount() < 3 || !relative.getName(1).toString().equals("models")) continue;
                        String json = Files.readString(model, StandardCharsets.UTF_8);
                        String redirected = WATER_TEXTURE.matcher(json).replaceAll("\"machineengine:block/water_$1\"");
                        if (redirected.equals(json)) continue;
                        Path target = overlay.resolve("assets").resolve(relative.toString());
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, redirected, StandardCharsets.UTF_8);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Where the bucket texture of a fluid is: {@code machineengine:item/fluid_bucket/<namespace>/<path>}. */
    public static @NotNull Key bucketTexture(@NotNull Key fluid) {
        return Key.key("machineengine", "item/fluid_bucket/" + fluid.namespace() + "/" + fluid.value());
    }

    /**
     * Draws a bucket texture for every fluid with its own bucket item ({@code bucket: <item>}): the vanilla
     * bucket with the water in it recolored to the fluid's color, at {@link #bucketTexture}. The item's model
     * points at it (Mekanized: {@code mekanized:<fluid>_bucket}).
     *
     * @return how many were written
     */
    public static int buckets(@NotNull VanillaClient client, @NotNull List<FluidType> fluids, @NotNull Path pack) throws IOException {
        Path root = pack.resolve("resourcepack/assets/machineengine/textures/item/fluid_bucket");
        deleteTree(root);
        BufferedImage water = client.texture("item/water_bucket");
        int count = 0;
        for (FluidType fluid : fluids) {
            if (!(fluid.items().bucket() instanceof FluidItems.Bucket.Item)) continue;
            int color = fluid.color() != null ? fluid.color() : fluid.look().tintFor(null);
            BufferedImage out = bucket(water, color);
            Path file = root.resolve(fluid.key().namespace()).resolve(fluid.key().value() + ".png");
            Files.createDirectories(file.getParent());
            ImageIO.write(out, "png", file.toFile());
            count++;
        }
        return count;
    }

    /** The vanilla water bucket with its water shaded with {@code color}; the bucket itself stays. */
    static BufferedImage bucket(BufferedImage water, int color) {
        int width = water.getWidth(), height = water.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        boolean[][] liquid = new boolean[height][width];
        double brightest = 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                liquid[y][x] = isWater(water.getRGB(x, y));
                if (liquid[y][x]) brightest = Math.max(brightest, luminance(water.getRGB(x, y)));
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = water.getRGB(x, y);
                if (!liquid[y][x]) {
                    out.setRGB(x, y, pixel);
                    continue;
                }
                double shade = 0.35 + 0.65 * luminance(pixel) / brightest;
                int r = (int) Math.round(((color >> 16) & 255) * shade), g = (int) Math.round(((color >> 8) & 255) * shade),
                        b = (int) Math.round((color & 255) * shade);
                out.setRGB(x, y, (pixel & 0xFF000000) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b));
            }
        }
        return out;
    }

    /** Water in the vanilla water bucket: blue (the bucket itself is grey metal). */
    private static boolean isWater(int argb) {
        if ((argb >>> 24) == 0) return false;
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return b - Math.max(r, g) > 20;
    }

    private static double luminance(int rgb) {
        return 0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255);
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

    /** A grid's animation: each tile's frames, how many, and the ticks per frame. */
    record Animation(List<List<BufferedImage>> tiles, int frames, int frameTime) {
    }

    /** Most frame pixels a grid animation may hold (64 MB of pixels), else it plays water's loop only. */
    private static final long MAX_PIXELS = 16L << 20;

    /**
     * The tiles' frames over one loop long enough for every texture's whole loop (water's and each fluid's,
     * each with its own frame time): frames shown every gcd of the frame times, for the lcm of the loops.
     */
    private static Animation animation(List<BufferedImage> water, int waterTime, Map<FluidTexture, Integer> tiles, FluidLook[] looks,
                                       boolean stillKind, Context context, int size, int columns, int capacity, List<String> notes) {
        List<List<BufferedImage>> strips = new ArrayList<>();
        List<Integer> times = new ArrayList<>();
        strips.add(water);
        times.add(waterTime);
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
                frameTime = waterTime;
            }
            strips.add(frames);
            times.add(frameTime);
        }
        int step = 0;
        long loop = 1;
        for (int i = 0; i < strips.size(); i++) {
            step = gcd(step, times.get(i));
            loop = lcm(loop, (long) strips.get(i).size() * times.get(i));
        }
        long side = (long) columns * size;
        long count = loop / step;
        if (count * side * side > MAX_PIXELS) {
            notes.add("the " + (stillKind ? "still" : "flowing") + " fluid animations together loop over " + loop
                    + " ticks, too long for one grid: they play over water's loop");
            step = waterTime;
            count = water.size();
        }
        List<List<BufferedImage>> timedTiles = new ArrayList<>();
        for (int i = 0; i < strips.size(); i++) timedTiles.add(timed(strips.get(i), (int) count, step, times.get(i)));
        return new Animation(timedTiles, (int) count, step);
    }

    /** {@code count} frames shown every {@code step} ticks, of a texture made for {@code ownTime} ticks per frame. */
    static List<BufferedImage> timed(List<BufferedImage> frames, int count, int step, int ownTime) {
        List<BufferedImage> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(frames.get((int) ((long) i * step / Math.max(1, ownTime) % frames.size())));
        return out;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? Math.max(1, a) : gcd(b, a % b);
    }

    private static long lcm(long a, long b) {
        long g = b;
        for (long x = a; x != 0; ) {
            long t = g % x;
            g = x;
            x = t;
        }
        return a / Math.max(1, g) * b;
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

    private static final java.util.regex.Pattern IMPORT =
            java.util.regex.Pattern.compile("^\\s*#(?:moj_import|include)\\s*<(?:([a-z0-9_.-]+):)?([^>]+)>", java.util.regex.Pattern.MULTILINE);

    /**
     * Every import of the overlay's shaders must be there, in the overlay or in the game: the game reads
     * every shader of a pack, and one missing import makes it drop the whole pack (a Sodium shader without
     * Sodium's include files did, on vanilla 26.2).
     */
    static void checkImports(VanillaClient client, Path overlay) throws IOException {
        Path assets = overlay.resolve("assets");
        if (!Files.isDirectory(assets)) return;
        try (Stream<Path> files = Files.walk(assets)) {
            for (Path shader : files.filter(file -> file.toString().matches(".*\\.(vsh|fsh|glsl)$")).toList()) {
                java.util.regex.Matcher matcher = IMPORT.matcher(Files.readString(shader, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String namespace = matcher.group(1) != null ? matcher.group(1) : "minecraft";
                    String path = "assets/" + namespace + "/shaders/include/" + matcher.group(2);
                    if (Files.isRegularFile(overlay.resolve(path)) || client.read(path) != null) continue;
                    throw new IOException(overlay.relativize(shader) + " imports " + namespace + ":" + matcher.group(2)
                            + ", which neither the pack nor the game has");
                }
            }
        }
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
            // Sodium warns about packs replacing the game's terrain shader, which Sodium does not use; ours is
            // only for players without Sodium (Sodium players get the patched Sodium shader).
            JsonArray ignored = new JsonArray();
            for (String name : List.of("terrain.vsh", "terrain.fsh", "rendertype_translucent.vsh", "rendertype_translucent.fsh")) ignored.add(name);
            JsonObject sodium = new JsonObject();
            sodium.add("ignored_shaders", ignored);
            root.add("sodium", sodium);
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
