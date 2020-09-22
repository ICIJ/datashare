package org.icij.datashare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
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
            newDeliverable(Paths.get(getDeleteOpt(cliProperties)).toUri().toURL()).delete(extensionsDir);
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
        return merge(deliverableRegistry.get(),listInstalled()).stream().
                filter(p -> Pattern.compile(patternString).matcher(String.join("%s %s %S",p.getInfoForPattern())).find()).
                collect(toSet());
    }

    public Set<T> list() {
        return merge(deliverableRegistry.get(), listInstalled());
    }

    private Set<T> merge(Set<T> registryDeliverables, Set<File> listInstalled) {
        Set<T> result = new LinkedHashSet<>(registryDeliverables);
        Set<File> installedDeliverables = new LinkedHashSet<>(listInstalled);
        installedDeliverables.removeAll(registryDeliverables.stream().map((d -> extensionsDir.resolve(d.getBasePath()).toFile())).collect(toSet()));
        result.addAll(installedDeliverables.stream().map(f -> {
            try {
                return newDeliverable(f.toURI().toURL());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }).collect(toSet()));
        return result;
    }

    public Set<File> listInstalled() {
        return stream(ofNullable(extensionsDir.toFile().listFiles()).orElse(new File[]{})).collect(Collectors.toSet());
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
}
