package fr.robie.machineengine.api.version;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code major.minor.patch[-qualifier]} version. Missing minor/patch parts default to 0.
 *
 * <p>Ordering follows semantic versioning: a version with a qualifier (e.g. {@code -SNAPSHOT})
 * sorts before the same version without one. {@link VersionRange} compares {@linkplain #base()
 * base versions} only, so {@code 0.1.0-SNAPSHOT} satisfies {@code [0.1,1.0)}.</p>
 */
public record Version(int major, int minor, int patch, @Nullable String qualifier) implements Comparable<Version> {
    private static final Pattern PATTERN = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+](.+))?");

    public Version {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version parts must not be negative");
        }
        if (qualifier != null && qualifier.isEmpty()) {
            qualifier = null;
        }
    }

    public static @NotNull Version of(int major, int minor, int patch) {
        return new Version(major, minor, patch, null);
    }

    /**
     * @throws IllegalArgumentException if {@code text} is not a version
     */
    public static @NotNull Version parse(@NotNull String text) {
        Matcher matcher = PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a version: '" + text + "'");
        }
        return new Version(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)),
                matcher.group(4));
    }

    /** @return this version without its qualifier */
    public @NotNull Version base() {
        return this.qualifier == null ? this : new Version(this.major, this.minor, this.patch, null);
    }

    @Override
    public int compareTo(@NotNull Version other) {
        int result = Integer.compare(this.major, other.major);
        if (result == 0) result = Integer.compare(this.minor, other.minor);
        if (result == 0) result = Integer.compare(this.patch, other.patch);
        if (result != 0) return result;
        if (Objects.equals(this.qualifier, other.qualifier)) return 0;
        if (this.qualifier == null) return 1;
        if (other.qualifier == null) return -1;
        return this.qualifier.compareTo(other.qualifier);
    }

    @Override
    public @NotNull String toString() {
        return this.major + "." + this.minor + "." + this.patch + (this.qualifier == null ? "" : "-" + this.qualifier);
    }
}
