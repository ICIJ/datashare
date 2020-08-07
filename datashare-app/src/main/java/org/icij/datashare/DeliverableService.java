package org.icij.datashare;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toSet;

public abstract class DeliverableService<T extends Deliverable> {
    final Logger logger = LoggerFactory.getLogger(getClass());
    protected final Path extensionsDir;
    protected final DeliverableRegistry<T> deliverableRegistry;

    abstract T newDeliverable(URL url);
    abstract DeliverableRegistry<T> createRegistry(InputStream pluginJsonContent);
    abstract String getDeleteOpt(Properties cliProperties);
    abstract String getInstallOpt(Properties cliProperties);
    abstract String getListOpt(Properties cliProperties);

    public DeliverableService(Path extensionsDir, InputStream inputStream) {
        this.extensionsDir = extensionsDir;
        this.deliverableRegistry = createRegistry(inputStream);
    }

    public void deleteFromCli(Properties cliProperties) throws IOException {
        try {
            delete(getDeleteOpt(cliProperties)); // plugin with id
        } catch (DeliverableRegistry.UnknownDeliverableException not_a_plugin) {
            delete(Paths.get(getDeleteOpt(cliProperties))); // from base dir
        }
    }

    public void downloadAndInstallFromCli(Properties cliProperties) throws IOException {
       try {
           downloadAndInstall(getInstallOpt(cliProperties)); // plugin with id
       } catch (DeliverableRegistry.UnknownDeliverableException not_a_plugin) {
           try {
               URL pluginUrl = new URL(getInstallOpt(cliProperties));
               downloadAndInstall(pluginUrl); // from url
           } catch (MalformedURLException not_url) {
               newDeliverable(Paths.get(getInstallOpt(cliProperties)).toUri().toURL()).install(extensionsDir); // from file
           }
       }
    }

    public Set<T> list(String patternString) {
        return deliverableRegistry.get().stream().
                        filter(p -> Pattern.compile(patternString).matcher(p.getId()).matches()).
                        collect(toSet());
    }

    public Set<T> list() {
        return deliverableRegistry.get();
    }

    public void downloadAndInstall(String extensionId) throws IOException {
        downloadAndInstall(deliverableRegistry.get(extensionId));
    }

    public void downloadAndInstall(URL url) throws IOException {
        downloadAndInstall(newDeliverable(url));
    }

    private void downloadAndInstall(T extension) throws IOException {
        File tmpFile = extension.download();
        extension.install(tmpFile, extensionsDir);
    }

    public void delete(String extensionId) throws IOException {
        deliverableRegistry.get(extensionId).delete(extensionsDir);
    }

    public void delete(Path pluginBaseDirectory) throws IOException {
        Path pluginDirectory = extensionsDir.resolve(pluginBaseDirectory);
        logger.info("removing plugin base directory {}", pluginDirectory);
        FileUtils.deleteDirectory(pluginDirectory.toFile());
    }
}
