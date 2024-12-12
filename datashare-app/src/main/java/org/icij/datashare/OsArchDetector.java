package org.icij.datashare;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;

import java.util.regex.Pattern;

public class OsArchDetector {
    private final OS os;
    private final ARCH arch;

    public OsArchDetector() {
        this.os = OS.fromSystem();
        this.arch = ARCH.fromSystem();
    }

    OsArchDetector(OS os, ARCH arch) {
        this.os = os;
        this.arch = arch;
    }

    enum OS {
        macos("mac.*"), linux(".*(nux|nix|aix).*"), windows("windows.*");
        private final Pattern osPattern;

        OS(String regexp) {
            this.osPattern = Pattern.compile(regexp);
        }

        static OS fromSystem() {
            return fromSystemString(System.getProperty("os.name"));
        }

        static OS fromSystemString(String osName) {
            String normalizedOsName = normalize(osName);
            return stream(values()).filter(os -> os.osPattern.matcher(normalizedOsName).matches())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(format("Unknown OS: %s", osName)));
        }
    }

    enum ARCH {
        aarch64("(aarch64|arm64).*"), x86_64("(amd64|x86_64).*");
        private final Pattern archPattern;

        ARCH(String archPattern) {
            this.archPattern = Pattern.compile(archPattern);
        }

        static ARCH fromSystem() {
            return fromSystemString(System.getProperty("os.arch"));
        }

        static ARCH fromSystemString(String archName) {
            String normalizedArchName = normalize(archName);
            return stream(values()).filter(os -> os.archPattern.matcher(normalizedArchName).matches())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(format("Unknown ARCH: %s", archName)));
        }
    }

    static String normalize(String string) {
        return ofNullable(string).orElse("").toLowerCase();
    }

    String osArchSuffix() {
        return os + "-" + arch;
    }
}
