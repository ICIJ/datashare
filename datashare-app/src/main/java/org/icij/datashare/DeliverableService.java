package org.icij.datashare;

import com.google.inject.Singleton;
import org.apache.tika.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toSet;

@Singleton
public abstract class DeliverableService<T extends Deliverable> {
    final Logger logger = LoggerFactory.getLogger(getClass());
    protected final Path extensionsDir;
    protected final DeliverableRegistry<T> deliverableRegistry;

    abstract T newDeliverable(URL url);
    abstract DeliverableRegistry<T> getRegistry(InputStream pluginJsonContent);

    public DeliverableService(Path extensionsDir, InputStream inputStream) {
        this.extensionsDir = extensionsDir;
        this.deliverableRegistry = getRegistry(inputStream);
    }

    public void downloadAndInstallFromCli(String extensionIdOrUrlOrFile) throws IOException {
        try {
            downloadAndInstall(extensionIdOrUrlOrFile); // extension with id
        } catch (DeliverableRegistry.UnknownDeliverableException not_an_extension) {
                URL extensionUrl = new URL(extensionIdOrUrlOrFile);
                downloadAndInstall(extensionUrl); // from url
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
        URL url = deliverableRegistry.get(extensionId).getUrl();
        File extensionFile = extensionsDir.resolve(FilenameUtils.getName(url.getPath())).toFile();
        logger.info("removing extension jar {}", extensionId);
        extensionFile.delete();
    }
}
