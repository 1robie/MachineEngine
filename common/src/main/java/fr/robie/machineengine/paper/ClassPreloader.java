package fr.robie.machineengine.paper;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads a plugin's own classes when it starts. The JVM loads classes on first use, from the jar
 * file; if the jar is replaced while the server runs (a rebuild on a dev server, an update copied
 * over it), a class first used later fails with {@code NoClassDefFoundError}. The shutdown save is
 * the worst place for that: machines would not be saved. With every class loaded up front, the
 * running plugin no longer reads its jar.
 */
public final class ClassPreloader {
    private ClassPreloader() {
    }

    /**
     * Loads (without initializing) every class under {@code packagePrefix} in the jar of {@code anchor}.
     * Classes that cannot load here (e.g. they need an optional plugin that is missing) are skipped.
     *
     * @param packagePrefix e.g. {@code fr/robie/machineengine/}
     * @param skipped       prefixes under it never to load, e.g. the nms modules of other Minecraft
     *                      versions: Java 25 classes make Paper 1.20.6's class loader throw (its ASM
     *                      reads every class first), which is not a {@link LinkageError}
     * @return how many classes were loaded
     */
    public static int preload(@NotNull Class<?> anchor, @NotNull String packagePrefix, @NotNull String... skipped) {
        Path jar;
        try {
            jar = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return 0; // not loaded from a jar (tests, IDE): nothing to protect
        }
        if (!jar.toString().endsWith(".jar")) return 0;
        ClassLoader loader = anchor.getClassLoader();
        int loaded = 0;
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(packagePrefix) || !name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                if (Arrays.stream(skipped).anyMatch(name::startsWith)) continue;
                try {
                    Class.forName(name.substring(0, name.length() - ".class".length()).replace('/', '.'), false, loader);
                    loaded++;
                } catch (ClassNotFoundException | LinkageError e) {
                    // Needs something absent on this server; it will not be used either.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return loaded;
    }
}
