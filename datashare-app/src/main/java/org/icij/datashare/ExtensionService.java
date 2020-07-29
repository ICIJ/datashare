package org.icij.datashare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toSet;
import static org.apache.commons.io.FilenameUtils.getName;

@Singleton
public class ExtensionService {
    final Logger logger = LoggerFactory.getLogger(getClass());
    public static final String DEFAULT_EXTENSION_REGISTRY_FILENAME = "extensions.json";
    public static final String EXTENSION_BASE_URL = "/extensions";
    private final Path extensionsDir;
    private final DeliverableRegistry<Extension> extensionRegistry;

    public ExtensionService(Path extensionsDir) {
        this(extensionsDir, ClassLoader.getSystemResourceAsStream(DEFAULT_EXTENSION_REGISTRY_FILENAME));
    }

    @Inject
    public ExtensionService(PropertiesProvider propertiesProvider) {
        this(Paths.get(propertiesProvider.get(PropertiesProvider.EXTENSIONS_DIR).orElse(getCurrentDirExtensionDirectory())));
    }

    public ExtensionService(Path extensionsDir, InputStream inputStream) {
        this.extensionsDir = extensionsDir;
        this.extensionRegistry = getExtensionRegistry(inputStream);
    }

    public Set<Extension> list(String patternString) {
        return extensionRegistry.get().stream().
                        filter(p -> Pattern.compile(patternString).matcher(p.id).matches()).
                        collect(toSet());
    }

    public Set<Extension> list() {
        return extensionRegistry.get();
    }

    public void downloadAndInstall(String extensionId) throws IOException {
        Extension extension = extensionRegistry.get(extensionId);
        download(extension.url);
    }

    public void downloadAndInstall(URL url) throws IOException {
        download(url);
    }

    File download(URL url) throws IOException {
        ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
        File jarFile = extensionsDir.resolve(getName(url.getFile())).toFile();
        logger.info("downloading from url {}", url);
        try (FileOutputStream fileOutputStream = new FileOutputStream(jarFile)) {
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            return jarFile;
        }
    }

    private DeliverableRegistry<Extension> getExtensionRegistry(InputStream pluginJsonContent) {
        try {
            return new ObjectMapper().readValue(pluginJsonContent, new TypeReference<DeliverableRegistry<Extension>>() {});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private static String getCurrentDirExtensionDirectory() {
        return "." + EXTENSION_BASE_URL;
    }
}
