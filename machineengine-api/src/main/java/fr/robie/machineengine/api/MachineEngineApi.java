package fr.robie.machineengine.api;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Version information of the MachineEngine API.
 *
 * <p>The API follows semantic versioning (see ARCHITECTURE.md §5). Addons declare the API
 * range they require when they register.</p>
 */
public final class MachineEngineApi {
    private static final String VERSION = loadVersion();

    private MachineEngineApi() {
    }

    /**
     * @return the version of the MachineEngine API on the classpath, e.g. {@code 0.1.0-SNAPSHOT}
     */
    public static @NotNull String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = MachineEngineApi.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                throw new IllegalStateException("version.properties is missing from the MachineEngine API jar");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the MachineEngine API version", e);
        }
    }
}
