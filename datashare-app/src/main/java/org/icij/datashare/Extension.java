package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
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

import static java.nio.file.Files.copy;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.*;

public class Extension implements Deliverable {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    static Pattern endsWithVersion = Pattern.compile("([a-zA-Z\\-.]*)-([0-9.]*)$");
    static Pattern endsWithExtension = Pattern.compile("(.*)(\\.[a-zA-Z]+$)");
    public static final String TMP_PREFIX = "tmp";
    public final String id;
    public final String name;
    public final String description;

    public final URL url;
    public final String version;
    public final Type type;

    @JsonCreator
    public Extension(@JsonProperty("id") String id,
                  @JsonProperty("name") String name,
                  @JsonProperty("version") String version,
                  @JsonProperty("description") String description,
                  @JsonProperty("url") URL url,
                  @JsonProperty("type") Type type){
        this.id = requireNonNull(id);
        this.url = url;
        this.name = name;
        this.version = version;
        this.description = description;
        this.type = type;
    }

    Extension(URL url, Type type) {
        Entry<String, String> res = extractIdVersion(requireNonNull(url, "an extension/plugin cannot be created with a null URL"));
        this.id = res.getKey();
        this.url = url;
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
    }

    @Override
    public void delete(Path installDir) throws IOException {
        Path extensionPath = installDir.resolve(getUrlFileName());
        logger.info("removing extension {} jar {}", id, extensionPath);
        extensionPath.toFile().delete();
    }

    static List<File> getPreviousVersionInstalled(File[] candidateFiles, String baseName) {
        return stream(candidateFiles).filter(f -> f.getName().startsWith(removePattern(endsWithVersion,baseName)) && endsWithVersion.matcher(getBaseName(f.getName())).matches()).collect(Collectors.toList());
    }

    static Entry<String,String> extractIdVersion(URL url){
        String baseName = removePattern(endsWithExtension,FilenameUtils.getName(url.getFile().replaceAll("/$","")));
        Matcher matcher = endsWithVersion.matcher(baseName);
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

    @Override public URL getUrl() { return url;}

    @Override public String getId() { return this.id;}

    @Override public String getName() { return name; }

    @Override public String getDescription() { return description; }

    @Override public String getVersion() { return version; }

    @Override public Type getType() { return type; }

    public Path getBasePath() {return Paths.get(getUrlFileName());}

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
}
