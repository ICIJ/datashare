package org.icij.datashare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toSet;

public class ExtensionService {
    public static final String DEFAULT_EXTENSION_REGISTRY_FILENAME = "extensions.json";
    public static final String EXTENSION_BASE_URL = "/extensions";
    private final Path extensionsDir;
    private final DeliverableRegistry<Extension> extensionRegistry;

    public ExtensionService(Path extensionsDir) {
        this(extensionsDir, ClassLoader.getSystemResourceAsStream(DEFAULT_EXTENSION_REGISTRY_FILENAME));
    }

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
