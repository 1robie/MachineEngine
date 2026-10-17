package fr.robie.machineengine.paper.fluid.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Sodium's terrain shader, for players who use Sodium (docs/FLUID_TYPES_PLAN.md §7, owner 2026-10-16).
 * Sodium draws terrain with its own shaders, so the game's patched shader never runs for them and they
 * would see the texture grid. From Sodium 0.9 (Minecraft 26.2) Sodium reads its shaders from resource
 * packs; before, from its own jar only, so nothing can be done there. The newest Sodium for the server's
 * Minecraft version is downloaded once from Modrinth (checked by SHA-1) into MachineEngine's cache.
 */
public record SodiumShaders(@NotNull String version, @NotNull String vertex, @NotNull String fragment,
                            @NotNull Map<String, String> includes) {
    public static final String VERTEX = "assets/sodium/shaders/blocks/block_layer_opaque.vsh";
    public static final String FRAGMENT = "assets/sodium/shaders/blocks/block_layer_opaque.fsh";
    /** Sodium's include files: the game reads every shader of a pack, so the patched one needs them even without Sodium. */
    public static final String INCLUDES = "assets/sodium/shaders/include/";
    private static final Pattern SODIUM_VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** Sodium versions whose shaders a resource pack can replace. */
    public static boolean overridable(@NotNull String sodiumVersion) {
        String bare = sodiumVersion.replaceFirst("^mc[^-]+-", "");
        Matcher matcher = SODIUM_VERSION.matcher(bare);
        if (!matcher.find()) return false;
        int major = Integer.parseInt(matcher.group(1)), minor = Integer.parseInt(matcher.group(2));
        return major > 0 || minor >= 9;
    }

    /**
     * Sodium's shaders for {@code minecraft}, or {@code null} when that Sodium can't take them from a pack
     * (before 0.9) or there is none.
     */
    public static @Nullable SodiumShaders load(@NotNull String minecraft, @NotNull Path cache) throws IOException {
        try {
            return download(minecraft, cache);
        } catch (IOException e) {
            // Modrinth unreachable: the Sodium downloaded before still does.
            SodiumShaders cached = cached(minecraft, cache);
            if (cached != null) return cached;
            throw e;
        }
    }

    /**
     * The newest Sodium for {@code minecraft} already in the cache (no network: used at enable, on the main
     * thread), or {@code null}.
     */
    public static @Nullable SodiumShaders cached(@NotNull String minecraft, @NotNull Path cache) throws IOException {
        if (!Files.isDirectory(cache)) return null;
        Path newest = null;
        try (var files = Files.list(cache)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("sodium-mc" + minecraft + "-") || !name.endsWith(".jar")) continue;
                if (newest == null || Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(newest)) > 0) newest = file;
            }
        }
        if (newest == null) return null;
        String version = newest.getFileName().toString().replaceFirst("^sodium-", "").replaceFirst("\\.jar$", "");
        return overridable(version) ? fromJar(newest, version) : null;
    }

    private static @Nullable SodiumShaders download(String minecraft, Path cache) throws IOException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        String query = "loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8)
                + "&game_versions=" + URLEncoder.encode("[\"" + minecraft + "\"]", StandardCharsets.UTF_8);
        JsonArray versions = JsonParser.parseString(get(http, "https://api.modrinth.com/v2/project/sodium/version?" + query)).getAsJsonArray();
        JsonObject chosen = null;
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            // Releases first; a beta when the version has no release yet (26.3).
            if (chosen == null || rank(version) > rank(chosen)
                    || rank(version) == rank(chosen) && version.get("date_published").getAsString().compareTo(chosen.get("date_published").getAsString()) > 0) {
                chosen = version;
            }
        }
        if (chosen == null) return null;
        String name = chosen.get("version_number").getAsString();
        if (!overridable(name)) return null;
        JsonObject file = null;
        for (JsonElement element : chosen.getAsJsonArray("files")) {
            JsonObject candidate = element.getAsJsonObject();
            boolean primary = candidate.has("primary") && candidate.get("primary").getAsBoolean();
            if (file == null || primary) file = candidate; // the primary file, else the first
            if (primary) break;
        }
        if (file == null) return null;
        String sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();
        Path jar = cache.resolve("sodium-" + name.replaceAll("[^A-Za-z0-9.+-]", "_") + ".jar");
        if (!Files.isRegularFile(jar) || !sha1.equalsIgnoreCase(sha1(jar))) download(http, file.get("url").getAsString(), sha1, jar);
        return fromJar(jar, name);
    }

    /**
     * Sodium's shaders from a Sodium jar the server owner gives ({@code world-fluids.sodium-jar}), or {@code null}
     * when that Sodium can't take them from a pack (before 0.9: its {@code fabric.mod.json} says which version).
     */
    public static @Nullable SodiumShaders fromJar(@NotNull Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) throw new IOException("world-fluids.sodium-jar: " + jar + " does not exist");
        String version;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry metadata = zip.getEntry("fabric.mod.json");
            if (metadata == null) metadata = zip.getEntry("META-INF/neoforge.mods.toml");
            if (metadata == null) throw new IOException(jar + " is not a Sodium jar");
            String text = read(zip, metadata.getName());
            Matcher matcher = Pattern.compile("\"?version\"?\\s*[:=]\\s*\"([^\"$]+)\"").matcher(text);
            version = matcher.find() ? matcher.group(1) : null;
            if (version == null) {
                // NeoForge's mods.toml says "${file.jarVersion}": the manifest has the version.
                ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
                Matcher implementation = Pattern.compile("Implementation-Version:\\s*(\\S+)").matcher(manifest == null ? "" : read(zip, manifest.getName()));
                version = implementation.find() ? implementation.group(1) : "?";
            }
        }
        return overridable(version) ? fromJar(jar, version) : null;
    }

    private static SodiumShaders fromJar(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Map<String, String> includes = new TreeMap<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(INCLUDES)) includes.put(entry.getName(), read(zip, entry.getName()));
            }
            return new SodiumShaders(name, read(zip, VERTEX), read(zip, FRAGMENT), includes);
        }
    }

    private static int rank(JsonObject version) {
        return switch (version.get("version_type").getAsString()) {
            case "release" -> 2;
            case "beta" -> 1;
            default -> 0;
        };
    }

    private static String read(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IOException("Sodium has no " + path);
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String get(HttpClient http, String url) throws IOException {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "MachineEngine (world fluids pack)").build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException(url + ": HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void download(HttpClient http, String url, String sha1, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path partial = Files.createTempFile(target.getParent(), "sodium-", ".part");
        try {
            HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                            .header("User-Agent", "MachineEngine (world fluids pack)").build(),
                    HttpResponse.BodyHandlers.ofFile(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() != 200) throw new IOException("Sodium download: HTTP " + response.statusCode());
            if (!sha1.equalsIgnoreCase(sha1(partial))) throw new IOException("the downloaded Sodium jar does not match Modrinth's checksum");
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Sodium download interrupted", e);
        } finally {
            Files.deleteIfExists(partial);
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
}
