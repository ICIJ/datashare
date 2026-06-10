package org.icij.datashare.tray;

import org.apache.commons.lang3.SystemUtils;

public enum OsFamily {
    MAC, WINDOWS, LINUX, OTHER;

    public static OsFamily current() {
        // Reuse commons-lang3's os.name classification (already a dependency, used elsewhere
        // in this module) rather than parsing os.name a third time. Unlike OsArchDetector.OS,
        // it degrades to OTHER instead of throwing on an unrecognised platform.
        if (SystemUtils.IS_OS_MAC) {
            return MAC;
        }
        if (SystemUtils.IS_OS_WINDOWS) {
            return WINDOWS;
        }
        if (SystemUtils.IS_OS_LINUX) {
            return LINUX;
        }
        return OTHER;
    }
}
