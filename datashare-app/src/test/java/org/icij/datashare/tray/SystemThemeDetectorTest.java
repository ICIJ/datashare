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
    public void test_linux_default_falls_back_to_light_gtk_theme() {
        // color-scheme 'default' is not a colour preference; fall back to the GTK theme.
        assertEquals(LIGHT, detector(OsFamily.LINUX, (t, cmd) ->
                String.join(" ", cmd).contains("color-scheme") ? "'default'" : "'Adwaita'").detect());
    }

    @Test
    public void test_linux_default_with_dark_gtk_theme_is_dark() {
        // 'default' colour-scheme but an explicitly dark GTK theme -> DARK (was wrongly LIGHT).
        assertEquals(DARK, detector(OsFamily.LINUX, (t, cmd) ->
                String.join(" ", cmd).contains("color-scheme") ? "'default'" : "'Adwaita-dark'").detect());
    }

    @Test
    public void test_linux_default_with_unreadable_gtk_theme_is_unknown() {
        // 'default' colour-scheme and the GTK theme read fails -> UNKNOWN (no blind guess).
        assertEquals(UNKNOWN, detector(OsFamily.LINUX, (t, cmd) -> {
            if (String.join(" ", cmd).contains("color-scheme")) {
                return "'default'";
            }
            throw new RuntimeException("no gtk-theme");
        }).detect());
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
