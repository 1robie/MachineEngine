package fr.robie.machineengine.api.version;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Maven-style version range: {@code [1.0,2.0)}, {@code (,1.5]}, {@code [2.0,)}, {@code [1.2]}
 * (exactly 1.2), or a bare version {@code 1.2}, which means "1.2 or newer".
 *
 * <p>Ranges compare {@linkplain Version#base() base versions}: qualifiers are ignored.</p>
 */
public record VersionRange(@Nullable Version lower, boolean lowerInclusive,
                           @Nullable Version upper, boolean upperInclusive) {

    public static final VersionRange ANY = new VersionRange(null, false, null, false);

    /**
     * @throws IllegalArgumentException if {@code text} is not a range
     */
    public static @NotNull VersionRange parse(@NotNull String text) {
        String range = text.trim();
        if (range.isEmpty() || range.equals("*")) {
            return ANY;
        }
        char first = range.charAt(0);
        if (first != '[' && first != '(') {
            return new VersionRange(Version.parse(range).base(), true, null, false);
        }
        char last = range.charAt(range.length() - 1);
        if (last != ']' && last != ')') {
            throw new IllegalArgumentException("Unterminated version range: '" + text + "'");
        }
        String body = range.substring(1, range.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            if (first != '[' || last != ']') {
                throw new IllegalArgumentException("A single-version range must be written [x]: '" + text + "'");
            }
            Version exact = Version.parse(body).base();
            return new VersionRange(exact, true, exact, true);
        }
        String lower = body.substring(0, comma).trim();
        String upper = body.substring(comma + 1).trim();
        return new VersionRange(
                lower.isEmpty() ? null : Version.parse(lower).base(), first == '[',
                upper.isEmpty() ? null : Version.parse(upper).base(), last == ']');
    }

    public boolean contains(@NotNull Version version) {
        Version base = version.base();
        if (this.lower != null) {
            int compared = base.compareTo(this.lower);
            if (compared < 0 || (compared == 0 && !this.lowerInclusive)) return false;
        }
        if (this.upper != null) {
            int compared = base.compareTo(this.upper);
            if (compared > 0 || (compared == 0 && !this.upperInclusive)) return false;
        }
        return true;
    }

    @Override
    public @NotNull String toString() {
        if (this.lower == null && this.upper == null) return "*";
        if (this.upper == null && this.lowerInclusive) return this.lower + "+";
        return (this.lowerInclusive ? "[" : "(")
                + (this.lower == null ? "" : this.lower) + ","
                + (this.upper == null ? "" : this.upper)
                + (this.upperInclusive ? "]" : ")");
    }
}
