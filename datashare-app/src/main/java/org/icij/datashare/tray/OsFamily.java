package org.icij.datashare.tray;

import java.util.Locale;

public enum OsFamily {
    MAC, WINDOWS, LINUX, OTHER;

    public static OsFamily fromName(String osName) {
        if (osName == null) {
            return OTHER;
        }
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) {
            return MAC;
        }
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("nix") || name.contains("nux") || name.contains("linux")) {
            return LINUX;
        }
        return OTHER;
    }

    public static OsFamily current() {
        return fromName(System.getProperty("os.name"));
    }
}
