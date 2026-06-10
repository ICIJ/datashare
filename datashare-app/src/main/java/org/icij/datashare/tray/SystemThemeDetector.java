package org.icij.datashare.tray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            if (out.contains("prefer-light") || out.contains("default")) {
                return Theme.LIGHT;
            }
            return Theme.UNKNOWN;
        } catch (Exception e) {
            LOGGER.warn("Could not detect Linux theme, defaulting to UNKNOWN", e);
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
            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IOException("Command exited " + process.exitValue()
                        + ": " + String.join(" ", command));
            }
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
    }
}
