package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.copy;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.getBaseName;
import static org.apache.commons.io.FilenameUtils.getExtension;

public class Extension implements Deliverable {
    @JsonIgnore
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    static Pattern extensionFormat = Pattern.compile("([[^\\W_][\\-.]]*)-v?([0-9.]*)(?<!-)([-\\w]*)?$"); //of form: id-with-numb3r-1.2.3-suffix with negative lookbehind for suffix dash
    static Pattern endsWithExtension = Pattern.compile("(.*)(\\.[a-zA-Z]+$)");
    public static final String TMP_PREFIX = "tmp";
    public final String id;
    public final String name;
    public final String description;
    public final URL url;
    public final URL homepage;
    public final String version;
    public final Type type;

    @JsonCreator
    public Extension(@JsonProperty("id") String id,
                  @JsonProperty("name") String name,
                  @JsonProperty("version") String version,
                  @JsonProperty("description") String description,
                  @JsonProperty("url") URL url,
                  @JsonProperty("homepage") URL homepage,
                  @JsonProperty("type") Type type){
        this.id = requireNonNull(id);
        this.url = url;
        this.homepage = homepage;
        this.name = name;
        this.version = version;
        this.description = description;
        this.type = type;
    }

    Extension(URL url, Type type) {
        Entry<String, String> res = extractIdVersion(requireNonNull(url, "an extension/plugin cannot be created with a null URL"));
        this.id = res.getKey();
        this.url = url;
        this.homepage = null;
        this.version = res.getValue();
        this.name = res.getKey();
        this.description = null;
        this.type = type;
    }

    Extension(URL url) {this(url, Type.UNKNOWN);}

    @Override
    public File download() throws IOException { return download(url);}

    @NotNull
    protected File download(URL url) throws IOException {
        ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
        File tmpFile = Files.createTempFile(TMP_PREFIX, "." + getExtension(url.toString())).toFile();
        logger.info("downloading from url {}", url);
        try (FileOutputStream fileOutputStream = new FileOutputStream(tmpFile)) {
           fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
           return tmpFile;
        }
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
        File[] candidateFiles = ofNullable(extensionsDir.toFile().listFiles((file, s) -> s.endsWith(".jar"))).orElse(new File[0]);
        List<File> previousVersionInstalled = getPreviousVersionInstalled(candidateFiles, getBaseName(getUrlFileName()));
        if (previousVersionInstalled.size() > 0) {
            logger.info("removing previous versions {}", previousVersionInstalled);
            previousVersionInstalled.forEach(File::delete);
        }
        logger.info("installing extension from file {} into {}", extensionFile, extensionsDir);
        copy(extensionFile.toPath(), extensionsDir.resolve(getUrlFileName()));
        if (isTemporaryFile(extensionFile)) extensionFile.delete();
        extensionsDir.resolve(getUrlFileName()).toFile().setExecutable(true);
    }

    @Override
    public void delete(Path installDir) throws IOException {
        Path extensionPath = installDir.resolve(getUrlFileName());
        if (extensionPath.toFile().exists()) {
            logger.info("removing extension {} jar: {}", id, extensionPath);
            extensionPath.toFile().delete();
        } else {
            logger.info("could not remove extension {} jar: {}", id, extensionPath);
        }
    }


    static List<File> getPreviousVersionInstalled(File[] candidateFiles, String baseName) {
        return Stream.of(candidateFiles)
                .filter(f -> {
                    String fileName = f.getName();
                    String baseFileName = removePattern(extensionFormat, baseName);
                    boolean startsWithBaseName = fileName.startsWith(baseFileName);
                    boolean matchesPattern = extensionFormat.matcher(getBaseName(fileName)).matches();
                    return startsWithBaseName && matchesPattern;
                })
                .collect(Collectors.toList());
    }

    static Entry<String,String> extractIdVersion(URL url){
        String baseName = removePattern(endsWithExtension,FilenameUtils.getName(url.getFile().replaceAll("/$","")));
        Matcher matcher = extensionFormat.matcher(baseName);
        if(matcher.matches()){
            return new AbstractMap.SimpleEntry<>(matcher.group(1),matcher.group(2));
        }
        return new AbstractMap.SimpleEntry<>(baseName,null);
    }

    static String removePattern(Pattern pattern, String string) {
        Matcher matcher = pattern.matcher(string);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return string;
    }

    @Override public URL getUrl() { return url; }

    @Override public URL getHomepage() { return homepage; }

    @Override public String getId() { return this.id; }

    @Override public String getName() { return name; }

    @Override public String getDescription() { return description; }

    @Override public String getVersion() { return version; }

    @Override public Type getType() { return type; }

    @JsonIgnore
    public Path getCanonicalPath() {
        return Paths.get(getUrlFileName());
    }

    @JsonIgnore
    public Path getLocalPath(Path installationDir) {
        try (Stream<Path> paths = Files.list(installationDir)) {
            return paths
                    .filter(entry -> {
                        String fileName = entry.getFileName().toString();
                        return fileName.startsWith(id);
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.info("Unable to list directories in {}", installationDir);
        }

        return null;
    }

    protected String getUrlFileName() { return FilenameUtils.getName(url.getFile().replaceAll("/$",""));}
    protected boolean isTemporaryFile(File extensionFile) { return extensionFile.getName().startsWith(Plugin.TMP_PREFIX);}

    @Override
    public String toString() {
        return "Extension id='" + id + '\'' + '\'' + ", version='" + version + '\'' + "url=" + url + '\'' + "type=" + type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Extension)) return false;
        Extension extension = (Extension) o;
        if(version == null || extension.version == null){
            if(version == null && extension.version == null)
                return id.equals(extension.id);
            return false;
        }
        return id.equals(extension.id) &&
                version.equals(extension.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public int compareTo(@NotNull Deliverable deliverable) {
        int idCompare = this.id.compareTo(deliverable.getId());
        return idCompare == 0 ? ofNullable(this.version).orElse("-1").compareTo(ofNullable(deliverable.getVersion()).orElse("-1")) : idCompare;
    }
}
