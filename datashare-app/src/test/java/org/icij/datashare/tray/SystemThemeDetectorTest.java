package org.icij.datashare.tray;

import org.junit.Test;

import static org.icij.datashare.tray.SystemThemeDetector.Theme.DARK;
import static org.icij.datashare.tray.SystemThemeDetector.Theme.LIGHT;
import static org.icij.datashare.tray.SystemThemeDetector.Theme.UNKNOWN;
import static org.junit.Assert.assertEquals;

public class SystemThemeDetectorTest {

    private static SystemThemeDetector detector(OsFamily os, SystemThemeDetector.CommandRunner runner) {
        return new SystemThemeDetector(os, runner);
    }

    @Test
    public void test_linux_prefer_dark() {
        assertEquals(DARK, detector(OsFamily.LINUX,
                (t, cmd) -> "'prefer-dark'").detect());
    }

    @Test
    public void test_linux_default_is_light() {
        assertEquals(LIGHT, detector(OsFamily.LINUX,
                (t, cmd) -> "'default'").detect());
    }

    @Test
    public void test_linux_prefer_light() {
        assertEquals(LIGHT, detector(OsFamily.LINUX,
                (t, cmd) -> "'prefer-light'").detect());
    }

    @Test
    public void test_linux_command_failure_is_unknown() {
        assertEquals(UNKNOWN, detector(OsFamily.LINUX,
                (t, cmd) -> { throw new RuntimeException("no gsettings"); }).detect());
    }

    @Test
    public void test_windows_light_theme() {
        assertEquals(LIGHT, detector(OsFamily.WINDOWS,
                (t, cmd) -> "    SystemUsesLightTheme    REG_DWORD    0x1").detect());
    }

    @Test
    public void test_windows_dark_theme() {
        assertEquals(DARK, detector(OsFamily.WINDOWS,
                (t, cmd) -> "    SystemUsesLightTheme    REG_DWORD    0x0").detect());
    }

    @Test
    public void test_windows_command_failure_is_unknown() {
        assertEquals(UNKNOWN, detector(OsFamily.WINDOWS,
                (t, cmd) -> { throw new RuntimeException("no reg"); }).detect());
    }

    @Test
    public void test_mac_dark() {
        assertEquals(DARK, detector(OsFamily.MAC,
                (t, cmd) -> "Dark").detect());
    }

    @Test
    public void test_mac_key_absent_is_light() {
        // `defaults read` exits non-zero when the key is absent (Light mode)
        assertEquals(LIGHT, detector(OsFamily.MAC,
                (t, cmd) -> { throw new RuntimeException("key not found"); }).detect());
    }

    @Test
    public void test_other_os_is_unknown() {
        assertEquals(UNKNOWN, detector(OsFamily.OTHER,
                (t, cmd) -> "whatever").detect());
    }
}
