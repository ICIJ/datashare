package org.icij.datashare.tray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class SystemThemeDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemThemeDetector.class);
    private static final long TIMEOUT_MS = 2000;

    public enum Theme { DARK, LIGHT, UNKNOWN }

    /** Seam over process execution; returns trimmed stdout or throws on failure/timeout. */
    @FunctionalInterface
    public interface CommandRunner {
        String run(long timeoutMillis, String... command) throws Exception;
    }

    private final OsFamily os;
    private final CommandRunner runner;

    public SystemThemeDetector(OsFamily os) {
        this(os, new ProcessCommandRunner());
    }

    SystemThemeDetector(OsFamily os, CommandRunner runner) {
        this.os = os;
        this.runner = runner;
    }

    public Theme detect() {
        switch (os) {
            case LINUX:
                return detectLinux();
            case WINDOWS:
                return detectWindows();
            case MAC:
                return detectMac();
            default:
                return Theme.UNKNOWN;
        }
    }

    private Theme detectLinux() {
        try {
            String out = runner.run(TIMEOUT_MS,
                    "gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                    .toLowerCase(Locale.ROOT);
            if (out.contains("prefer-dark")) {
                return Theme.DARK;
            }
            if (out.contains("prefer-light")) {
                return Theme.LIGHT;
            }
            // 'default' means "no explicit colour-scheme preference", NOT "light":
            // many desktops ship a dark default. Fall back to the GTK theme name.
            return detectLinuxGtkTheme();
        } catch (Exception e) {
            LOGGER.warn("Could not detect Linux theme, defaulting to UNKNOWN", e);
            return Theme.UNKNOWN;
        }
    }

    private Theme detectLinuxGtkTheme() {
        try {
            String theme = runner.run(TIMEOUT_MS,
                    "gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
                    .toLowerCase(Locale.ROOT);
            return theme.contains("dark") ? Theme.DARK : Theme.LIGHT;
        } catch (Exception e) {
            LOGGER.warn("Could not read GTK theme, defaulting to UNKNOWN", e);
            return Theme.UNKNOWN;
        }
    }

    private Theme detectWindows() {
        try {
            String out = runner.run(TIMEOUT_MS,
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "SystemUsesLightTheme")
                    .toLowerCase(Locale.ROOT);
            if (out.contains("0x1")) {
                return Theme.LIGHT;
            }
            if (out.contains("0x0")) {
                return Theme.DARK;
            }
            return Theme.UNKNOWN;
        } catch (Exception e) {
            LOGGER.warn("Could not detect Windows theme, defaulting to UNKNOWN", e);
            return Theme.UNKNOWN;
        }
    }

    private Theme detectMac() {
        try {
            String out = runner.run(TIMEOUT_MS, "defaults", "read", "-g", "AppleInterfaceStyle");
            return out.trim().equalsIgnoreCase("Dark") ? Theme.DARK : Theme.LIGHT;
        } catch (Exception e) {
            // key absent => Light mode
            return Theme.LIGHT;
        }
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public String run(long timeoutMillis, String... command) throws Exception {
            // Discard stderr (we never parse it) so the child can never block on a full
            // stderr pipe, and so there is one fewer stream to drain.
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw new IOException("Command timed out: " + String.join(" ", command));
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Command exited " + process.exitValue()
                            + ": " + String.join(" ", command));
                }
                try (InputStream in = process.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
            } finally {
                // Release the process and its pipe file descriptors on every path
                // (timeout, non-zero exit, success). destroy() is a no-op once exited.
                process.destroy();
                closeQuietly(process.getInputStream());
                closeQuietly(process.getOutputStream());
            }
        }

        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }
}
