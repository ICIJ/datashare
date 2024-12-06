package org.icij.datashare;

import static org.apache.commons.io.FilenameUtils.getExtension;

import java.net.MalformedURLException;
import java.net.URL;
import org.apache.commons.io.FilenameUtils;

public class DeliverableHelper {
    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final String ARCH = System.getProperty("os.arch").toLowerCase();
    private static final boolean IS_MACOS = OS.contains("mac");
    private static final boolean IS_WINDOWS = OS.contains("windows");
    private static final boolean IS_UNIX = OS.contains("nix") || OS.contains("nux") || OS.indexOf("aix") > 0;
    private static final boolean IS_X86_64 = ARCH.contains("amd64") || ARCH.contains("x86_64");
    private static final boolean IS_ARM = ARCH.contains("aarch64") || ARCH.contains("arm64");

    static String getUrlFileName(URL url) { return FilenameUtils.getName(url.getFile().replaceAll("/$",""));}

    static URL hostSpecificUrl(URL url, String version) {
        String extFileName = getUrlFileName(url);
        String hostSpecificName = extFileName.replace("-" + version, "") + hostSpecificSuffix() + "-" + version;
        String urlFile = url.getFile();
        StringBuilder b = new StringBuilder(urlFile);
        int last = urlFile.lastIndexOf(extFileName);
        b.replace(last, last + extFileName.length(), "");
        urlFile = b.toString();
        String hostSpecificFile = urlFile + hostSpecificName;
        try {
            return new URL(url.getProtocol(), url.getHost(), hostSpecificFile);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    static String hostSpecificSuffix() {
        String arch;
        if (IS_ARM) {
            // We assume 64 arch here...
            arch = "aarch64";
        } else if (IS_X86_64) {
            arch = "x86_64";
        } else {
            throw new RuntimeException("unsupported architecture " + ARCH);
        }
        String os;
        if (IS_WINDOWS) {
            os = "windows";
        } else if (IS_MACOS) {
            os = "macos";
        } else if (IS_UNIX) {
            os = "linux";
        } else {
            throw new RuntimeException("unsupported os " + OS);
        }
        return "-" + os + "-" + arch;
    }

    static String getExtensionFileExt(String fileName) {
        // We have to strip the version otherwise the patch it's identified as the extension for binary ext
        if (fileName.isEmpty()) {
            return "";
        }
        String[] split = fileName.split("-");
        String[] lastSplit = split[split.length - 1].split("\\.");
        if (lastSplit.length == 1) {
            return "";
        }
        return getExtension(lastSplit[lastSplit.length - 1]);
    }
}
