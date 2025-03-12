package org.icij.datashare;

import static org.apache.commons.io.FilenameUtils.getExtension;

import java.net.MalformedURLException;
import java.net.URL;

import org.apache.commons.io.FilenameUtils;

public class DeliverableHelper {
    static String getUrlFileName(URL url) { return FilenameUtils.getName(url.getFile().replaceAll("/$",""));}

    static URL hostSpecificUrl(OsArchDetector osArchDetector, URL url, String version) {
        String fileName = url.getFile();
        String fileNameWithOsAndArch = fileName.replace("-" + version, String.format("-%s-%s", osArchDetector.osArchSuffix(), version));
        try {
            return new URL(url.getProtocol(), url.getHost(), fileNameWithOsAndArch);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
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
