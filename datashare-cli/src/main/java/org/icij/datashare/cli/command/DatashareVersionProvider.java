package org.icij.datashare.cli.command;

import picocli.CommandLine;

import java.io.InputStream;
import java.util.Properties;

public class DatashareVersionProvider implements CommandLine.IVersionProvider {
    /** Returns a single-element array containing "datashare <version>", read from /git.properties. */
    @Override
    public String[] getVersion() throws Exception {
        Properties versions = new Properties();
        try (InputStream gitProperties = getClass().getResourceAsStream("/git.properties")) {
            if (gitProperties != null) {
                versions.load(gitProperties);
            }
        }
        String version = versions.getProperty("git.build.version", "unknown");
        return new String[]{"datashare " + version};
    }
}
