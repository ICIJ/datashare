package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.jetbrains.annotations.NotNull;

import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.getBaseName;
import static org.apache.commons.io.FilenameUtils.getExtension;

public class Plugin extends Extension {
    private static final Pattern versionBeginsWithV = Pattern.compile("v[0-9.]*");
    private static final int  MAX_ENTRY_COUNT = 10_000;
    private static final long MAX_ENTRY_SIZE  = 100L * 1024 * 1024;  // 100 MB per entry
    private static final long MAX_TOTAL_SIZE  = 1024L * 1024 * 1024; // 1 GB total
    public final List<String> extensions;

    @JsonCreator
    public Plugin(@JsonProperty("id") String id,
                  @JsonProperty("name") String name,
                  @JsonProperty("version") String version,
                  @JsonProperty("description") String description,
                  @JsonProperty("url") URL url,
                  @JsonProperty("homepage") URL homepage,
                  @JsonProperty("extensions") List<String> extensions
    ){
        super(id, name, version, description, url, homepage, Type.PLUGIN);
        this.extensions = ofNullable(extensions).orElse(List.of());
    }

    public Plugin(String id, String name, String version, String description, URL url, URL homepage){
        this(id, name, version, description, url, homepage, null);
    }

    Plugin(URL url) {
        super(url,Type.PLUGIN);
        this.extensions = List.of();
    }

    @Override
    public void delete(Path pluginsDirectory) throws IOException {
        Path pluginDirectory = this.getLocalPath(pluginsDirectory);
        if (pluginDirectory != null && pluginDirectory.toFile().exists()) {
            logger.info("removing plugin base directory {}", pluginDirectory);
            FileUtils.deleteDirectory(pluginDirectory.toFile());
        } else {
            logger.info("could not remove plugin base directory {} jar: {}", id, pluginDirectory);
        }
    }

    @NotNull
    public File download() throws IOException {
        ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
        File tmpFile = Files.createTempFile(TMP_PREFIX, "." + getExtension(url.toString())).toFile();
        logger.info("downloading from url {}", url);
        try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile)) {
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            return tmpFile;
        }
    }

    @Override
    public void install(File pluginFile, Path pluginsDir) throws IOException {
        File[] pluginDirDirectories = pluginsDir.toFile()
            .listFiles((dir, fileName) -> dir.toPath().resolve(fileName).toFile().isDirectory());
        File[] candidateFiles = ofNullable(pluginDirDirectories).orElse(new File[0]);
        List<File> previousVersionInstalled = getPreviousVersionInstalled(candidateFiles, getBaseName(getUrlFileName()));
        if (previousVersionInstalled.size() > 0) {
            logger.info("removing previous versions {}", previousVersionInstalled);
            for (File file : previousVersionInstalled) {
                FileUtils.deleteDirectory(file);
            }
        }
        logger.info("installing plugin from file {} into {}", pluginFile, pluginsDir);

        Path canonicalPluginsDir = pluginsDir.toAbsolutePath().normalize();
        long totalExtractedSize = 0;
        int entryCount = 0;

        InputStream is = new BufferedInputStream(new FileInputStream(pluginFile));
        if (pluginFile.getName().endsWith("gz")) {
            is = new BufferedInputStream(new GZIPInputStream(is));
        }
        try (ArchiveInputStream zippedArchiveInputStream = new ArchiveStreamFactory().createArchiveInputStream(is)) {
            ArchiveEntry entry;
            while ((entry = zippedArchiveInputStream.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new IOException("Archive contains too many entries (limit: " + MAX_ENTRY_COUNT + ")");
                }
                Path resolvedPath = canonicalPluginsDir.resolve(entry.getName()).normalize();
                if (!resolvedPath.startsWith(canonicalPluginsDir)) {
                    throw new IOException("Archive entry escapes target directory: " + entry.getName());
                }
                final File outputFile = resolvedPath.toFile();
                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw new IllegalStateException("Couldn't create directory " + outputFile.getAbsolutePath());
                    }
                } else {
                    try (OutputStream outputFileStream = new FileOutputStream(outputFile)) {
                        totalExtractedSize += copyBounded(zippedArchiveInputStream, outputFileStream, MAX_ENTRY_SIZE);
                    }
                    if (totalExtractedSize > MAX_TOTAL_SIZE) {
                        throw new IOException("Archive total extracted size exceeds limit (" + MAX_TOTAL_SIZE + " bytes)");
                    }
                }
            }
        } catch (ArchiveException e) {
            throw new RuntimeException(e);
        }
        if (isTemporaryFile(pluginFile)) pluginFile.delete();
    }

    @Override
    public Path getCanonicalPath() {
        if (url.getHost().equals("github.com") || extensionFormat.matcher(getBaseName(getUrlFileName())).matches()) {
            if (versionBeginsWithV.matcher(version).matches()) {
                return Paths.get(id + "-" + version.substring(1));
            }
            return Paths.get(id + "-" + version);
        }
        return Paths.get(id);
    }

    protected boolean isHostSpecific() {
        return false;
    }

    private long copyBounded(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Archive entry exceeds size limit (" + maxBytes + " bytes)");
            }
            out.write(buf, 0, n);
        }
        return total;
    }
}
