package org.icij.datashare;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class Plugin extends Extension {
    @JsonIgnore
    Pattern versionBeginsWithV = Pattern.compile("v[0-9.]*");

    @JsonCreator
        public Plugin(@JsonProperty("id") String id,
                      @JsonProperty("name") String name,
                      @JsonProperty("version") String version,
                      @JsonProperty("description") String description,
                      @JsonProperty("url") URL url){
        super(id, name, version, description, url, Type.PLUGIN);
    }

    public Plugin(URL url){
        super(url);
        this.type = Type.PLUGIN;
    }

    public String getId() {return id;}

    public URL getDeliverableUrl() {
        if (url.getHost().equals("github.com")) {
            try {
                return new URL(url.toString() + "/archive/" + version + ".tar.gz");
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        return url;
    }

    public Path getBaseDirectory() {
        if (url.getHost().equals("github.com")) {
            if (versionBeginsWithV.matcher(version).matches()) {
                return Paths.get(id + "-" + version.substring(1));
            }
            return Paths.get(id + "-" + version);
        }
        return Paths.get(id);
    }

    public void delete(Path pluginsDirectory) throws IOException {
        Path pluginDirectory = pluginsDirectory.resolve(getBaseDirectory());
        logger.info("removing plugin base directory {}", pluginDirectory);
        FileUtils.deleteDirectory(pluginDirectory.toFile());
    }

    public void install(File extensionFile, Path extensionsDir) throws IOException {
        logger.info("installing plugin from file {} into {}", extensionFile, extensionsDir);

        InputStream is = new BufferedInputStream(new FileInputStream(extensionFile));
        if (extensionFile.getName().endsWith("gz")) {
            is = new BufferedInputStream(new GZIPInputStream(is));
        }
        try (ArchiveInputStream zippedArchiveInputStream = new ArchiveStreamFactory().createArchiveInputStream(is)) {
            ArchiveEntry entry;
            while ((entry = zippedArchiveInputStream.getNextEntry()) != null) {
                final File outputFile = new File(extensionsDir.toFile(), entry.getName());
                if (entry.isDirectory()) {
                    if (!outputFile.exists()) {
                        if (!outputFile.mkdirs()) {
                            throw new IllegalStateException(String.format("Couldn't create directory %s.", outputFile.getAbsolutePath()));
                        }
                    }
                } else {
                    final OutputStream outputFileStream = new FileOutputStream(outputFile);
                    IOUtils.copy(zippedArchiveInputStream, outputFileStream);
                    outputFileStream.close();
                }
            }
        } catch (ArchiveException e) {
            throw new RuntimeException(e);
        }
        if (extensionFile.getName().startsWith(Plugin.TMP_PREFIX)) extensionFile.delete();
    }
}
