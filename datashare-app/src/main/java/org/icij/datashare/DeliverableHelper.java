package org.icij.datashare;

import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.getExtension;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;

public class DeliverableHelper {
    enum OS {
        macos("mac.*"), linux(".*(nux|nix|aix).*"), windows("windows.*");
        private final Pattern osPattern;

        OS(String regexp) {
            this.osPattern = Pattern.compile(regexp);
        }

        static OS fromSystem() {return fromSystemString(System.getProperty("os.name"));}
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

        static ARCH fromSystem() {return fromSystemString(System.getProperty("os.arch"));}
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
    static String getUrlFileName(URL url) { return FilenameUtils.getName(url.getFile().replaceAll("/$",""));}

    static URL hostSpecificUrl(URL url, String version) {
        String fileName = url.getFile();
        String fileNameWithOsAndArch = fileName.replace(version, String.format("%s-%s", osArchSuffix(), version));
        try {
            return new URL(url.getProtocol(), url.getHost(), fileNameWithOsAndArch);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    static String osArchSuffix() {
        return OS.fromSystem() + "-" + ARCH.fromSystem();
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
