package fr.robie.machineengine.api.version;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Version(int major, int minor, int patch, @Nullable String qualifier) implements Comparable<Version> {
    private static final Pattern PATTERN = Pattern.compile("^" +
                    "(0|[1-9]\\d*)" +
                    "(?:\\.(0|[1-9]\\d*))?" +
                    "(?:\\.(0|[1-9]\\d*))?" +
                    "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "$"
    );

    public Version {
        if (major < 0) {
            throw new IllegalArgumentException("Major version must not be negative: " + major);
        }
        if (minor < 0) {
            throw new IllegalArgumentException("Minor version must not be negative: " + minor);
        }
        if (patch < 0) {
            throw new IllegalArgumentException("Patch version must not be negative: " + patch);
        }

        qualifier = normalizeQualifier(qualifier);
    }

    /**
     * Creates a version without a qualifier.
     */
    public static @NotNull Version of(int major, int minor, int patch) {
        return new Version(major, minor, patch, null);
    }

    /**
     * Creates a version with a qualifier.
     */
    public static @NotNull Version of(int major, int minor, int patch, @Nullable String qualifier) {
        return new Version(major, minor, patch, qualifier);
    }

    /**
     * Parses a version string.
     *
     * @param text version text
     * @return parsed version
     * @throws IllegalArgumentException if the text is not a valid version
     */
    public static @NotNull Version parse(@NotNull String text) {
        Objects.requireNonNull(text, "text");

        String value = text.trim();

        if (value.isEmpty()) {
            throw new IllegalArgumentException("Version must not be empty");
        }

        Matcher matcher = PATTERN.matcher(value);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a valid version: '" + text + "'");
        }

        return new Version(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)),
                matcher.group(4)
        );
    }

    /**
     * Returns this version without its qualifier.
     */
    public @NotNull Version base() {
        return this.qualifier == null ? this : new Version(this.major, this.minor, this.patch, null);
    }

    /**
     * Returns whether this version is greater than or equal to {@code other}.
     */
    public boolean isAtLeast(@NotNull Version other) {
        return this.compareTo(other) >= 0;
    }

    /**
     * Returns whether this version is less than or equal to {@code other}.
     */
    public boolean isAtMost(@NotNull Version other) {
        return this.compareTo(other) <= 0;
    }

    /**
     * Returns whether this version is strictly greater than {@code other}.
     */
    public boolean isAfter(@NotNull Version other) {
        return this.compareTo(other) > 0;
    }

    /**
     * Returns whether this version is strictly less than {@code other}.
     */
    public boolean isBefore(@NotNull Version other) {
        return this.compareTo(other) < 0;
    }

    /**
     * Returns whether this version has a qualifier.
     */
    public boolean isQualified() {
        return this.qualifier != null;
    }

    /**
     * Returns whether this version is a stable release.
     */
    public boolean isStable() {
        return this.qualifier == null;
    }

    @Override
    public int compareTo(@NotNull Version other) {
        Objects.requireNonNull(other, "Other version must not be null");

        int result = Integer.compare(this.major, other.major);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(this.minor, other.minor);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(this.patch, other.patch);

        if (result != 0) {
            return result;
        }

        return compareQualifiers(this.qualifier, other.qualifier);
    }

    /**
     * Returns the version in canonical form.
     *
     * <p>Missing minor and patch components are normalized:</p>
     */
    @Override
    public @NotNull String toString() {
        return this.major + "." + this.minor + "." + this.patch + (this.qualifier == null ? "" : "-" + this.qualifier);
    }

    private static @Nullable String normalizeQualifier(@Nullable String qualifier) {
        if (qualifier == null) {
            return null;
        }

        String value = qualifier.trim();

        return value.isEmpty() ? null : value;
    }

    private static int compareQualifiers(@Nullable String first, @Nullable String second) {
        if (Objects.equals(first, second)) {
            return 0;
        }

        if (first == null) {
            return 1;
        }

        if (second == null) {
            return -1;
        }

        String[] firstParts = first.split("\\.");
        String[] secondParts = second.split("\\.");

        int length = Math.min(firstParts.length, secondParts.length);

        for (int i = 0; i < length; i++) {
            String left = firstParts[i];
            String right = secondParts[i];

            if (left.equals(right)) {
                continue;
            }

            boolean leftNumeric = isNumeric(left);
            boolean rightNumeric = isNumeric(right);

            if (leftNumeric && rightNumeric) {
                int result = compareNumericIdentifiers(left, right);

                if (result != 0) {
                    return result;
                }
            } else if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            } else {
                int result = left.compareTo(right);

                if (result != 0) {
                    return result;
                }
            }
        }

        return Integer.compare(firstParts.length, secondParts.length);
    }

    private static boolean isNumeric(@NotNull String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }

        return !value.isEmpty();
    }

    private static int compareNumericIdentifiers(@NotNull String first, @NotNull String second) {
        String normalizedFirst = stripLeadingZeros(first);
        String normalizedSecond = stripLeadingZeros(second);

        int lengthResult = Integer.compare(normalizedFirst.length(), normalizedSecond.length());

        if (lengthResult != 0) {
            return lengthResult;
        }

        return normalizedFirst.compareTo(normalizedSecond);
    }

    private static @NotNull String stripLeadingZeros(@NotNull String value) {
        int index = 0;

        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }

        return value.substring(index);
    }
}