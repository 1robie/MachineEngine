package fr.robie.machineengine.api.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void parsesAndOrdersVersions() {
        assertEquals(new Version(1, 2, 0, null), Version.parse("1.2"));
        assertEquals(new Version(0, 1, 0, "SNAPSHOT"), Version.parse("0.1.0-SNAPSHOT"));
        assertTrue(Version.parse("0.1.0-SNAPSHOT").compareTo(Version.parse("0.1.0")) < 0);
        assertTrue(Version.parse("1.10.0").compareTo(Version.parse("1.9.9")) > 0);
        assertEquals("26.10.0-SNAPSHOT", Version.parse("26.10-SNAPSHOT").toString());
        assertThrows(IllegalArgumentException.class, () -> Version.parse("one"));
    }

    @Test
    void rangesFollowMavenSyntaxAndIgnoreQualifiers() {
        VersionRange range = VersionRange.parse("[0.1,1.0)");
        assertTrue(range.contains(Version.parse("0.1.0-SNAPSHOT")));
        assertTrue(range.contains(Version.parse("0.9.9")));
        assertFalse(range.contains(Version.parse("1.0.0")));

        assertTrue(VersionRange.parse("2.0").contains(Version.parse("7.1")), "a bare version means 'or newer'");
        assertFalse(VersionRange.parse("2.0").contains(Version.parse("1.9")));
        assertTrue(VersionRange.parse("(,1.5]").contains(Version.parse("1.5")));
        assertFalse(VersionRange.parse("(1.0,)").contains(Version.parse("1.0")));
        assertTrue(VersionRange.parse("[1.2]").contains(Version.parse("1.2.0")));
        assertFalse(VersionRange.parse("[1.2]").contains(Version.parse("1.2.1")));
        assertTrue(VersionRange.parse("*").contains(Version.parse("0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> VersionRange.parse("[1.0,2.0"));
    }
}
