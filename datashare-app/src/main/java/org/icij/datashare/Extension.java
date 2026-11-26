package org.icij.datashare;

import static java.nio.file.Files.copy;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.getBaseName;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Extension implements Deliverable {
    @JsonIgnore
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    static Pattern extensionFormat = Pattern.compile("([[^\\W_]\\-.]*)-v?([0-9.]*)(?<!-)([-\\w]*)?$");
    //of form: id-with-numb3r-1.2.3-suffix with negative lookbehind for suffix dash
    static Pattern endsWithExtension = Pattern.compile("(.*)(\\.[a-zA-Z]+$)");
    public static final String TMP_PREFIX = "tmp";
    static final long MAX_DOWNLOAD_SIZE = 1024L * 1024 * 1024; // 1 GB
    static final int CONNECT_TIMEOUT_MS = 30_000;
    static final int READ_TIMEOUT_MS = 60_000; // maximum silence of the remote, not the total download time
    public final String id;
    public final String name;
    public final String description;
    public final URL url;
    public final URL homepage;
    public final String version;
    public final Type type;
    @JsonProperty("sha256")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sha256; // optional: when set in the registry, the download is verified against it
    protected boolean hostSpecific;

    @JsonCreator
    public Extension(@JsonProperty("id") String id, @JsonProperty("name") String name,
                     @JsonProperty("version") String version, @JsonProperty("description") String description,
                     @JsonProperty("url") URL url, @JsonProperty("homepage") URL homepage,
                     @JsonProperty("type") Type type) {
        this.id = requireNonNull(id);
        this.url = url;
        this.homepage = homepage;
        this.name = name;
        this.version = version;
        this.description = description;
        this.type = type;
        this.hostSpecific = this.isHostSpecific();
    }

    Extension(URL url, Type type) {
        Entry<String, String> res =
                extractIdVersion(requireNonNull(url, "an extension/plugin cannot be created with a null URL"));
        this.id = res.getKey();
        this.url = url;
        this.homepage = null;
        this.version = res.getValue();
        this.name = res.getKey();
        this.description = null;
        this.type = type;
        this.hostSpecific = isHostSpecific();
    }

    Extension(URL url) {
        this(url, Type.UNKNOWN);
    }

    @Override
    public File download() throws IOException {
        URL hostSpecificUrl = url;
        if (this.hostSpecific) {
            hostSpecificUrl = DeliverableHelper.hostSpecificUrl(new OsArchDetector(), url, version);
        }
        return download(hostSpecificUrl);
    }

    @NotNull
    protected File download(URL url) throws IOException {
        String ext = DeliverableHelper.getExtensionFileExt(url.toString());
        return download(url, ext.isEmpty() ? "" : "." + ext, MAX_DOWNLOAD_SIZE);
    }

    @NotNull File download(URL url, String suffix, long maxBytes) throws IOException {
        MessageDigest digest = sha256Digest();
        File tmpFile = Files.createTempFile(temporaryDirectory(), TMP_PREFIX, suffix).toFile();
        logger.info("downloading from url {}", url);
        try {
            long transferred;
            try (InputStream remote = openStream(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
                 InputStream in = digest == null ? remote : new DigestInputStream(remote, digest);
                 FileOutputStream out = new FileOutputStream(tmpFile)) {
                transferred = out.getChannel().transferFrom(Channels.newChannel(in), 0, maxBytes + 1);
            } catch (IOException downloadFailed) {
                throw new InvalidDeliverableException("download failed: " + url, downloadFailed);
            }
            if (transferred > maxBytes) {
                throw new InvalidDeliverableException("download exceeds size limit (" + maxBytes + " bytes): " + url);
            }
            if (digest != null) {
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equalsIgnoreCase(sha256)) {
                    throw new InvalidDeliverableException(
                            "sha256 mismatch for " + id + ": expected " + sha256 + " but got " + actual);
                }
            }
            return tmpFile;
        } catch (IOException | RuntimeException e) {
            FileUtils.deleteQuietly(tmpFile);
            throw e;
        }
    }

    /** Opens the url with timeouts, so a remote that never answers cannot hold the caller forever. */
    static InputStream openStream(URL url, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        return connection.getInputStream();
    }

    /** The digest to feed while downloading, or null when this deliverable has no sha256 to check. */
    private MessageDigest sha256Digest() {
        if (sha256 == null) {
            return null;
        }
        if (hostSpecific) {
            logger.warn("sha256 of {} is ignored: one hash cannot match the binaries of every os/arch", id);
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    /** Read at each call (the JDK caches it at class load), so the location follows the java.io.tmpdir property. */
    static Path temporaryDirectory() {
        return Paths.get(System.getProperty("java.io.tmpdir"));
    }

    public void install(Path extensionsDir) throws IOException {
        try {
            install(Paths.get(url.toURI()).toFile(), extensionsDir);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void install(File extensionFile, Path extensionsDir) throws IOException {
        String ext = DeliverableHelper.getExtensionFileExt(extensionFile.getName());
        File[] candidateFiles;
        FilenameFilter filenameFilter;
        if (!ext.isEmpty()) {
            filenameFilter = (file, s) -> s.endsWith("." + ext);
        } else {
            filenameFilter = (file, s) -> DeliverableHelper.getExtensionFileExt(s).isEmpty();
        }
        candidateFiles = ofNullable(extensionsDir.toFile().listFiles(filenameFilter)).orElse(new File[0]);
        List<File> previousVersionInstalled =
                getPreviousVersionInstalled(candidateFiles, getBaseName(getUrlFileName()));
        if (!previousVersionInstalled.isEmpty()) {
            logger.info("removing previous versions {}", previousVersionInstalled);
            previousVersionInstalled.forEach(File::delete);
        }
        logger.info("installing extension from file {} into {}", extensionFile, extensionsDir);
        copy(extensionFile.toPath(), extensionsDir.resolve(getUrlFileName()));
        extensionsDir.resolve(getUrlFileName()).toFile().setExecutable(true);
    }

    @Override
    public void delete(Path installDir) throws IOException {
        Path extensionPath = installDir.resolve(getUrlFileName());
        if (extensionPath.toFile().exists()) {
            logger.info("removing extension {} binary: {}", id, extensionPath);
            extensionPath.toFile().delete();
        } else {
            logger.info("could not remove extension {} binary: {}", id, extensionPath);
        }
    }

    static List<File> getPreviousVersionInstalled(File[] candidateFiles, String baseName) {
        return Stream.of(candidateFiles).filter(f -> {
            // same id, not only the same prefix: my-plugin-1.1.0 must not match my-plugin-extra-2.0.0
            String candidateBaseName = getBaseName(f.getName());
            return extensionFormat.matcher(candidateBaseName).matches() &&
                   removePattern(extensionFormat, candidateBaseName).equals(removePattern(extensionFormat, baseName));
        }).collect(Collectors.toList());
    }

    static Entry<String, String> extractIdVersion(URL url) {
        String baseName = removePattern(endsWithExtension, FilenameUtils.getName(url.getFile().replaceAll("/$", "")));
        Matcher matcher = extensionFormat.matcher(baseName);
        if (matcher.matches()) {
            return new AbstractMap.SimpleEntry<>(matcher.group(1), matcher.group(2));
        }
        return new AbstractMap.SimpleEntry<>(baseName, null);
    }

    static String removePattern(Pattern pattern, String string) {
        Matcher matcher = pattern.matcher(string);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return string;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public URL getHomepage() {
        return homepage;
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public Type getType() {
        return type;
    }

    @JsonIgnore
    public Path getCanonicalPath() {
        return Paths.get(getUrlFileName());
    }

    @JsonIgnore
    public Path getLocalPath(Path installationDir) {
        try (Stream<Path> paths = Files.list(installationDir)) {
            return paths.filter(entry -> {
                String fileName = entry.getFileName().toString();
                return fileName.startsWith(id);
            }).findFirst().orElse(null);
        } catch (IOException e) {
            logger.info("Unable to list directories in {}", installationDir);
        }

        return null;
    }

    protected String getUrlFileName() {
        return DeliverableHelper.getUrlFileName(url);
    }

    @Override
    public String toString() {
        return "Extension id='" + id + '\'' + '\'' + ", version='" + version + '\'' + "url=" + url + '\'' + "type=" +
               type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Extension extension))
            return false;
        if (version == null || extension.version == null) {
            if (version == null && extension.version == null)
                return id.equals(extension.id);
            return false;
        }
        return id.equals(extension.id) && version.equals(extension.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public int compareTo(@NotNull Deliverable deliverable) {
        int idCompare = this.id.compareTo(deliverable.getId());
        return idCompare == 0 ?
               ofNullable(this.version).orElse("-1").compareTo(ofNullable(deliverable.getVersion()).orElse("-1")) :
               idCompare;
    }

    protected boolean isHostSpecific() {
        return !FilenameUtils.getName(this.url.getFile().replaceAll("/$", "")).endsWith(".jar");
    }

    /**
     * The downloaded or given file cannot be installed: failed download, size limit, sha256 mismatch, refused
     * archive. A local write error while downloading (temp file, full disk) is also reported as a failed download.
     */
    public static class InvalidDeliverableException extends IOException {
        public InvalidDeliverableException(String message) {
            super(message);
        }

        public InvalidDeliverableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
