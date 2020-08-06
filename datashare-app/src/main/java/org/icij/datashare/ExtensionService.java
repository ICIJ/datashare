package org.icij.datashare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.*;

public class ExtensionService extends DeliverableService<Extension> {
    public static final String DEFAULT_EXTENSION_REGISTRY_FILENAME = "extensions.json";
    public static final String EXTENSION_BASE_URL = "/extensions";

    @Inject
    public ExtensionService(PropertiesProvider propertiesProvider) {
        this(Paths.get(propertiesProvider.get(PropertiesProvider.PLUGINS_DIR).orElse("." + EXTENSION_BASE_URL)));
    }

    public ExtensionService(Path extensionsDir) { this(extensionsDir, ClassLoader.getSystemResourceAsStream(DEFAULT_EXTENSION_REGISTRY_FILENAME));}
    public ExtensionService(Path extensionsDir, InputStream inputStream) { super(extensionsDir, inputStream);}

    @Override Extension newDeliverable(URL url) { return new Extension(url);}

    @Override
    DeliverableRegistry<Extension> createRegistry(InputStream pluginJsonContent) {
        try {
            return new ObjectMapper().readValue(pluginJsonContent, new TypeReference<DeliverableRegistry<Extension>>() {});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override String getDeleteOpt(Properties cliProperties) { return cliProperties.getProperty(EXTENSION_DELETE_OPT);}
    @Override String getInstallOpt(Properties cliProperties) { return cliProperties.getProperty(EXTENSION_INSTALL_OPT);}
    @Override String getListOpt(Properties cliProperties) { return cliProperties.getProperty(EXTENSION_LIST_OPT);}
}
