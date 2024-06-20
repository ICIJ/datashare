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
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.stream;
import static java.util.Comparator.reverseOrder;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;

public abstract class DeliverableService<T extends Deliverable> {
    final Logger logger = LoggerFactory.getLogger(getClass());
    protected final Path deliverablesDir;
    protected final DeliverableRegistry<T> deliverableRegistry;

    abstract T newDeliverable(URL url);
    abstract DeliverableRegistry<T> createRegistry(InputStream pluginJsonContent);
    abstract String getDeleteOpt(Properties cliProperties);
    abstract String getInstallOpt(Properties cliProperties);
    abstract String getListOpt(Properties cliProperties);

    public DeliverableService(Path deliverableDir, InputStream inputStream) {
        this.deliverablesDir = deliverableDir;
        this.deliverableRegistry = createRegistry(inputStream);
    }

    public void deleteFromCli(Properties cliProperties) throws IOException {
        try {
            delete(getDeleteOpt(cliProperties)); // plugin with id
        } catch (DeliverableRegistry.UnknownDeliverableException not_a_plugin) {
            newDeliverable(Paths.get(getDeleteOpt(cliProperties)).toUri().toURL()).delete(deliverablesDir);
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
               newDeliverable(Paths.get(getInstallOpt(cliProperties)).toUri().toURL()).install(deliverablesDir); // from file
           }
       }
    }

    public Set<DeliverablePackage> list(String patternString) {
        return merge(deliverableRegistry.search(patternString), listInstalled(patternString));
    }

    public Set<DeliverablePackage> list() {
        return merge(deliverableRegistry.get(), listInstalled());
    }

    private SortedSet<DeliverablePackage> merge(Set<T> registryDeliverables, Set<File> listInstalled) {
        SortedSet<DeliverablePackage> installedDeliverables = listInstalled.stream().sorted(reverseOrder()).map(f -> { //reverseOrder() for having latest versions
            try {
                T installedDeliverable = newDeliverable(f.toURI().toURL());
                return new DeliverablePackage(installedDeliverable, deliverablesDir, deliverableRegistry.deliverableMap.get(installedDeliverable.getId()));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toCollection(TreeSet::new));
        installedDeliverables.addAll(registryDeliverables.stream().map(d -> new DeliverablePackage(null, deliverablesDir, d)).collect(toSet()));
        return installedDeliverables;
    }

    public Set<File> listInstalled() {
        return listInstalled(".*");
    }

    public Set<File> listInstalled(String patternString) {
        Pattern pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
        return stream(ofNullable(deliverablesDir.toFile().listFiles()).orElse(new File[]{})).filter(f -> pattern.matcher(f.getName()).find()).collect(Collectors.toSet());
    }

    public void downloadAndInstall(String id) throws IOException {
        downloadAndInstall(deliverableRegistry.get(id));
    }

    public void downloadAndInstall(URL url) throws IOException {
        downloadAndInstall(newDeliverable(url));
    }

    private void downloadAndInstall(T deliverable) throws IOException {
        File tmpFile = deliverable.download();
        deliverable.install(tmpFile, deliverablesDir);
    }

    public void delete(String id) throws IOException {
        Predicate<DeliverablePackage> predicate = d -> d.reference().getId().equals(id) || d.reference().getUrl().getPath().equals(id);
        List<DeliverablePackage> deliverables = list().stream().filter(predicate).collect(Collectors.toList());
        for (DeliverablePackage deliverable: deliverables) {
            // A deliverable can have several references, if it's installed using
            // the registry or directly from a remote URL.
            for (Deliverable ref: deliverable.references()) {
                ref.delete(deliverablesDir);
            }
        }
    }
}
