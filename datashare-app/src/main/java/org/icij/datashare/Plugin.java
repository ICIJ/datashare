package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.function.IORunnable;
import java.io.*;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import org.jetbrains.annotations.NotNull;
import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.getBaseName;
import static org.apache.commons.io.FilenameUtils.getExtension;

public class Plugin extends Extension {
    private static final Pattern versionBeginsWithV = Pattern.compile("v[0-9.]*");
    private static final int MAX_ENTRY_COUNT = 10_000;
    private static final long MAX_ENTRY_SIZE = 100L * 1024 * 1024;  // 100 MB per entry
    private static final long MAX_TOTAL_SIZE = 1024L * 1024 * 1024; // 1 GB total
    public final List<String> extensions;

    @JsonCreator
    public Plugin(@JsonProperty("id") String id, @JsonProperty("name") String name,
                  @JsonProperty("version") String version, @JsonProperty("description") String description,
                  @JsonProperty("url") URL url, @JsonProperty("homepage") URL homepage,
                  @JsonProperty("extensions") List<String> extensions) {
        super(id, name, version, description, url, homepage, Type.PLUGIN);
        this.extensions = ofNullable(extensions).orElse(List.of());
    }

    public Plugin(String id, String name, String version, String description, URL url, URL homepage) {
        this(id, name, version, description, url, homepage, null);
    }

    Plugin(URL url) {
        super(url, Type.PLUGIN);
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
        return download(url, "." + getExtension(url.toString()), MAX_DOWNLOAD_SIZE);
    }

    @Override
    public void install(File pluginFile, Path pluginsDir) throws IOException {
        install(pluginFile, pluginsDir, () -> {});
    }

    /**
     * Extracts and checks the archive in a staging directory, runs beforeInstall, then installs the content.
     * A refused archive or a failing beforeInstall leaves the plugins directory untouched.
     */
    void install(File pluginFile, Path pluginsDir, IORunnable beforeInstall) throws IOException {
        Path stagingDir = Files.createTempDirectory(temporaryDirectory(), TMP_PREFIX);
        try {
            extract(pluginFile, stagingDir);
            beforeInstall.run();
            installExtracted(stagingDir, pluginsDir);
        } finally {
            FileUtils.deleteQuietly(stagingDir.toFile());
        }
    }

    private void extract(File pluginFile, Path targetDir) throws IOException {
        Path canonicalTargetDir = targetDir.toAbsolutePath().normalize();
        long totalExtractedSize = 0;
        int entryCount = 0;

        try (InputStream file = new BufferedInputStream(new FileInputStream(pluginFile));
             InputStream is = pluginFile.getName().endsWith("gz") ?
                     new BufferedInputStream(new GZIPInputStream(file)) : file;
             ArchiveInputStream<?> archive = new ArchiveStreamFactory().createArchiveInputStream(is)) {
            ArchiveEntry entry;
            while ((entry = nextEntry(archive)) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new InvalidDeliverableException(
                            "Archive contains too many entries (limit: " + MAX_ENTRY_COUNT + ")");
                }
                Path resolvedPath = resolve(canonicalTargetDir, entry);
                try {
                    if (entry.isDirectory()) {
                        Files.createDirectories(resolvedPath);
                    } else {
                        Files.createDirectories(resolvedPath.getParent()); // archives may have no directory entries
                        if (Files.isDirectory(resolvedPath)) {
                            throw conflictingEntries();
                        }
                        // any other failed open is a local failure (permission, disk full), not the archive's fault
                        try (OutputStream outputFileStream = new FileOutputStream(resolvedPath.toFile())) {
                            totalExtractedSize += copyBounded(archive, outputFileStream, MAX_ENTRY_SIZE);
                        }
                    }
                } catch (FileAlreadyExistsException fileWhereDirectoryIsNeeded) {
                    throw conflictingEntries();
                }
                if (totalExtractedSize > MAX_TOTAL_SIZE) {
                    throw new InvalidDeliverableException(
                            "Archive total extracted size exceeds limit (" + MAX_TOTAL_SIZE + " bytes)");
                }
            }
            if (entryCount == 0) {
                // nothing to install: refusing here keeps the installed version and its extensions untouched
                throw new InvalidDeliverableException("Archive has no entry");
            }
        } catch (ArchiveException | ZipException | EOFException e) {
            throw notReadable(e);
        }
    }

    private static ArchiveEntry nextEntry(ArchiveInputStream<?> archive) throws InvalidDeliverableException {
        try {
            return archive.getNextEntry();
        } catch (IOException e) {
            throw notReadable(e);
        }
    }

    /** The entry name comes from the archive: it is never put in a message, it could forge log lines. */
    private static Path resolve(Path canonicalTargetDir, ArchiveEntry entry) throws InvalidDeliverableException {
        try {
            Path resolvedPath = canonicalTargetDir.resolve(entry.getName()).normalize();
            if (!resolvedPath.startsWith(canonicalTargetDir)) {
                throw new InvalidDeliverableException("Archive entry escapes target directory");
            }
            return resolvedPath;
        } catch (InvalidPathException e) {
            throw new InvalidDeliverableException("Archive entry has an invalid name");
        }
    }

    /** A file entry and a directory entry with the same path. No cause nor path: they hold the raw entry name. */
    private static InvalidDeliverableException conflictingEntries() {
        return new InvalidDeliverableException("Archive entries conflict with each other");
    }

    private static InvalidDeliverableException notReadable(Exception cause) {
        return new InvalidDeliverableException("not a readable plugin archive", cause);
    }

    /**
     * Installs the extracted content, then removes the previous versions. An existing directory with the same
     * name is replaced, not merged. If a copy fails, what was created is removed and what was replaced is put
     * back, so the plugins directory is left as it was. If putting it back fails too, the replaced version is
     * kept in its temp directory, whose path is logged, so it can be recovered by hand.
     */
    private void installExtracted(Path extractedDir, Path pluginsDir) throws IOException {
        logger.info("installing plugin {} into {}", id, pluginsDir);
        List<String> names = List.of(ofNullable(extractedDir.toFile().list()).orElse(new String[0]));
        Path replacedDir = Files.createTempDirectory(temporaryDirectory(), TMP_PREFIX);
        List<String> created = new ArrayList<>();
        try {
            for (String name : names) {
                File target = pluginsDir.resolve(name).toFile();
                if (target.exists()) {
                    FileUtils.moveToDirectory(target, replacedDir.toFile(), false);
                }
                created.add(name);
                File extracted = extractedDir.resolve(name).toFile();
                if (extracted.isDirectory()) {
                    FileUtils.copyDirectory(extracted, target);
                } else {
                    FileUtils.copyFile(extracted, target);
                }
            }
        } catch (IOException | RuntimeException copyFailed) {
            created.forEach(name -> FileUtils.deleteQuietly(pluginsDir.resolve(name).toFile()));
            boolean restored = true;
            for (File replaced : ofNullable(replacedDir.toFile().listFiles()).orElse(new File[0])) {
                try {
                    FileUtils.moveToDirectory(replaced, pluginsDir.toFile(), false);
                } catch (IOException restoreFailed) {
                    restored = false;
                    logger.error("cannot put back {} after a failed install of {}, it is kept in {}",
                                 replaced.getName(), id, replacedDir, restoreFailed);
                }
            }
            if (restored) {
                FileUtils.deleteQuietly(replacedDir.toFile());
            }
            throw copyFailed;
        }
        FileUtils.deleteQuietly(replacedDir.toFile());

        File[] pluginDirDirectories =
                pluginsDir.toFile().listFiles((dir, fileName) -> dir.toPath().resolve(fileName).toFile().isDirectory());
        File[] candidateFiles = ofNullable(pluginDirDirectories).orElse(new File[0]);
        List<File> previousVersionInstalled =
                getPreviousVersionInstalled(candidateFiles, getBaseName(getUrlFileName())).stream()
                        .filter(previous -> !names.contains(previous.getName())).toList();
        if (!previousVersionInstalled.isEmpty()) {
            logger.info("removing previous versions {}", previousVersionInstalled);
            for (File file : previousVersionInstalled) {
                FileUtils.deleteDirectory(file);
            }
        }
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
        while ((n = readArchive(in, buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new InvalidDeliverableException("Archive entry exceeds size limit (" + maxBytes + " bytes)");
            }
            out.write(buf, 0, n);
        }
        return total;
    }

    /** A failing read is the archive's fault (truncated, corrupt), unlike a failing write. */
    private static int readArchive(InputStream in, byte[] buf) throws InvalidDeliverableException {
        try {
            return in.read(buf);
        } catch (IOException e) {
            throw notReadable(e);
        }
    }
}
