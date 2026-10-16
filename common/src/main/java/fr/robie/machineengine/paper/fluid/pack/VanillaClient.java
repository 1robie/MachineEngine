package fr.robie.machineengine.paper.fluid.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * The official Minecraft client's files the pack builder starts from: the water shaders and textures,
 * and the biome water colors (docs/FLUID_TYPES_PLAN.md §7, owner's answer 2: the official client jar,
 * downloaded from Mojang). Nothing of it is shipped: the jar is downloaded once per version into
 * MachineEngine's cache, checked against Mojang's SHA-1, or read from a path the server owner gives.
 */
public final class VanillaClient implements Closeable {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final @Nullable ZipFile jar;
    private final @Nullable Path directory;

    private VanillaClient(@Nullable ZipFile jar, @Nullable Path directory) {
        this.jar = jar;
        this.directory = directory;
    }

    /** A client jar, or a folder holding its files (dev checks). */
    public static @NotNull VanillaClient open(@NotNull Path path) throws IOException {
        return Files.isDirectory(path) ? new VanillaClient(null, path) : new VanillaClient(new ZipFile(path.toFile()), null);
    }

    /**
     * The client jar of {@code version}: {@code setting} is {@code download} (cached in {@code cache}) or a
     * path. Downloading takes a while; call it off the main thread.
     */
    public static @NotNull Path locate(@NotNull String setting, @NotNull String version, @NotNull Path cache) throws IOException {
        if (!setting.equalsIgnoreCase("download")) {
            Path given = Path.of(setting);
            if (!Files.exists(given)) throw new IOException("world-fluids.client-jar: " + given + " does not exist");
            return given;
        }
        Path cached = cache.resolve("minecraft-client-" + version + ".jar");
        if (Files.isRegularFile(cached)) return cached;
        Files.createDirectories(cache);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        JsonObject manifest = json(http, MANIFEST);
        String versionUrl = null;
        for (JsonElement entry : manifest.getAsJsonArray("versions")) {
            JsonObject object = entry.getAsJsonObject();
            if (object.get("id").getAsString().equals(version)) versionUrl = object.get("url").getAsString();
        }
        if (versionUrl == null) throw new IOException("Mojang's version list has no Minecraft " + version);
        JsonObject client = json(http, versionUrl).getAsJsonObject("downloads").getAsJsonObject("client");
        Path partial = cache.resolve("minecraft-client-" + version + ".jar.part");
        try {
            HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(client.get("url").getAsString()))
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(partial,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() != 200) throw new IOException("client jar download: HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("client jar download interrupted", e);
        }
        String sha1 = sha1(partial);
        if (!sha1.equalsIgnoreCase(client.get("sha1").getAsString())) {
            Files.deleteIfExists(partial);
            throw new IOException("the downloaded client jar does not match Mojang's checksum");
        }
        Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return cached;
    }

    private static JsonObject json(HttpClient http, String url) throws IOException {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException(url + ": HTTP " + response.statusCode());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** A file of the client, e.g. {@code assets/minecraft/shaders/core/terrain.vsh}; {@code null} when absent. */
    public byte @Nullable [] read(@NotNull String path) throws IOException {
        if (this.directory != null) {
            Path file = this.directory.resolve(path);
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        ZipEntry entry = this.jar == null ? null : this.jar.getEntry(path);
        if (entry == null) return null;
        try (InputStream in = this.jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    public @Nullable String text(@NotNull String path) throws IOException {
        byte[] bytes = read(path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** A vanilla texture, e.g. {@code block/water_still}. */
    public @NotNull BufferedImage texture(@NotNull String path) throws IOException {
        byte[] bytes = read("assets/minecraft/textures/" + path + ".png");
        if (bytes == null) throw new IOException("the client has no texture " + path);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("unreadable texture " + path);
        return image;
    }

    /** The animation frame time of a vanilla texture ({@code .mcmeta}), 1 when it has none. */
    public int frameTime(@NotNull String path) throws IOException {
        String meta = text("assets/minecraft/textures/" + path + ".png.mcmeta");
        if (meta == null) return 1;
        JsonObject animation = JsonParser.parseString(meta).getAsJsonObject().getAsJsonObject("animation");
        return animation != null && animation.has("frametime") ? animation.get("frametime").getAsInt() : 1;
    }

    /** Every biome's water color (0xRRGGBB), sorted. */
    public @NotNull List<Integer> waterColors() throws IOException {
        TreeSet<Integer> colors = new TreeSet<>();
        if (this.directory != null) {
            Path list = this.directory.resolve("water_colors.json");
            if (Files.isRegularFile(list)) {
                for (JsonElement color : JsonParser.parseString(Files.readString(list)).getAsJsonArray()) colors.add(color.getAsInt());
            }
            return new ArrayList<>(colors);
        }
        var entries = this.jar.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith("data/minecraft/worldgen/biome/") || !name.endsWith(".json")) continue;
            try (InputStream in = this.jar.getInputStream(entry)) {
                JsonObject effects = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("effects");
                JsonElement color = effects == null ? null : effects.get("water_color");
                if (color == null) continue;
                colors.add(color.getAsJsonPrimitive().isNumber() ? color.getAsInt() : Integer.parseInt(color.getAsString().replace("#", ""), 16));
            }
        }
        return new ArrayList<>(colors);
    }

    @Override
    public void close() throws IOException {
        if (this.jar != null) this.jar.close();
    }
}
