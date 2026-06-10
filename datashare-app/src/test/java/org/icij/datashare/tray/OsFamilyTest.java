package org.icij.datashare.tray;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OsFamilyTest {
    @Test
    public void test_parses_macos() {
        assertEquals(OsFamily.MAC, OsFamily.fromName("Mac OS X"));
        assertEquals(OsFamily.MAC, OsFamily.fromName("Darwin"));
    }

    @Test
    public void test_parses_windows() {
        assertEquals(OsFamily.WINDOWS, OsFamily.fromName("Windows 11"));
    }

    @Test
    public void test_parses_linux() {
        assertEquals(OsFamily.LINUX, OsFamily.fromName("Linux"));
    }

    @Test
    public void test_unknown_is_other() {
        assertEquals(OsFamily.OTHER, OsFamily.fromName("SunOS"));
        assertEquals(OsFamily.OTHER, OsFamily.fromName(null));
    }
}
