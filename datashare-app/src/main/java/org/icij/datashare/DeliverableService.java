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
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static java.util.Arrays.stream;
import static java.util.Comparator.reverseOrder;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.PropertiesProvider.ALLOW_INSECURE_ADDON_URL_INSTALL_OPT;

public abstract class DeliverableService<T extends Deliverable> {
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
    final Logger logger = LoggerFactory.getLogger(getClass());
    protected final Path deliverablesDir;
    protected final DeliverableRegistry<T> deliverableRegistry;
    private final boolean allowInsecureUrlInstall;

    abstract T newDeliverable(URL url);

    abstract DeliverableRegistry<T> createRegistry(InputStream pluginJsonContent);

    abstract String getDeleteOpt(Properties cliProperties);

    abstract String getInstallOpt(Properties cliProperties);

    abstract String getListOpt(Properties cliProperties);

    public DeliverableService(Path deliverableDir, InputStream inputStream) {
        this(deliverableDir, inputStream, false);
    }

    protected DeliverableService(Path deliverableDir, InputStream inputStream, boolean allowInsecureUrlInstall) {
        this.deliverablesDir = deliverableDir;
        this.deliverableRegistry = createRegistry(inputStream);
        this.allowInsecureUrlInstall = allowInsecureUrlInstall;
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
                Optional<T> registered = deliverableRegistry.findByUrl(pluginUrl);
                if (registered.isPresent()) {
                    downloadAndInstall(registered.get().getId()); // registry url
                } else {
                    downloadAndInstall(pluginUrl); // from url
                }
            } catch (MalformedURLException not_url) {
                newDeliverable(Paths.get(getInstallOpt(cliProperties)).toUri().toURL()).install(
                        deliverablesDir); // from file
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
        SortedSet<DeliverablePackage> installedDeliverables =
                listInstalled.stream().sorted(reverseOrder()).map(f -> { //reverseOrder() for having latest versions
                    try {
                        T installedDeliverable = newDeliverable(f.toURI().toURL());
                        return new DeliverablePackage(installedDeliverable, deliverablesDir,
                                                      deliverableRegistry.deliverableMap.get(
                                                              installedDeliverable.getId()));
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toCollection(TreeSet::new));
        installedDeliverables.addAll(
                registryDeliverables.stream().map(d -> new DeliverablePackage(null, deliverablesDir, d))
                                    .collect(toSet()));
        return installedDeliverables;
    }

    public Set<File> listInstalled() {
        return listInstalled(".*");
    }

    public Set<File> listInstalled(String patternString) {
        Pattern pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
        return stream(ofNullable(deliverablesDir.toFile().listFiles()).orElse(new File[] {})).filter(
                f -> pattern.matcher(f.getName()).find()).collect(Collectors.toSet());
    }

    public void downloadAndInstall(String id) throws IOException {
        downloadAndInstall(deliverableRegistry.get(id));
    }

    public void downloadAndInstall(URL url) throws IOException {
        downloadAndInstall(newDeliverable(url));
    }

    /**
     * Entry point for caller-supplied URLs (HTTP API). A URL of the registry installs the matching registry
     * entry. Any other URL is refused, unless allowInsecureAddonUrlInstall is set and the URL is https
     * without user info.
     *
     * @throws UrlNotAllowedException if the url is not allowed
     */
    public void downloadAndInstallFromRemote(URL url) throws IOException {
        Optional<T> registered = deliverableRegistry.findByUrl(url);
        if (registered.isPresent()) {
            downloadAndInstall(registered.get().getId());
        } else if (isAllowedUnregistered(url)) {
            logger.warn("installing from unregistered url {} ({} is set)", url, ALLOW_INSECURE_ADDON_URL_INSTALL_OPT);
            downloadAndInstall(url);
        } else {
            throw new UrlNotAllowedException(url, allowInsecureUrlInstall ?
                    "an unregistered url must be https, without user info or control characters" :
                    "it is not a url of the registry");
        }
    }

    boolean isAllowedUnregistered(URL url) {
        return allowInsecureUrlInstall && "https".equals(url.getProtocol()) && url.getUserInfo() == null &&
               !CONTROL_CHARACTERS.matcher(url.toString()).find();
    }

    /**
     * Whether the option is on.
     *
     * @throws IllegalArgumentException if its value is neither true nor false; CommonMode calls this at launch
     *                                  so a wrong value stops the start instead of failing at the first install
     */
    public static boolean allowsInsecureUrlInstall(PropertiesProvider propertiesProvider) {
        String value = propertiesProvider.get(ALLOW_INSECURE_ADDON_URL_INSTALL_OPT).orElse("false");
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(
                    ALLOW_INSECURE_ADDON_URL_INSTALL_OPT + " must be true or false, got: " + value);
        }
        return Boolean.parseBoolean(value);
    }

    public static class UrlNotAllowedException extends RuntimeException {
        public UrlNotAllowedException(URL url, String reason) {
            // the url comes from the caller: control characters are replaced so it cannot forge log lines
            super("url not allowed, " + reason + ": " + CONTROL_CHARACTERS.matcher(url.toString()).replaceAll("_"));
        }
    }

    private void downloadAndInstall(T deliverable) throws IOException {
        File tmpFile = deliverable.download();
        try {
            deliverable.install(tmpFile, deliverablesDir);
        } finally {
            FileUtils.deleteQuietly(tmpFile);
        }
    }

    public void delete(String id) throws IOException {
        Predicate<DeliverablePackage> predicate =
                d -> d.reference().getId().equals(id) || d.reference().getUrl().getPath().equals(id);
        List<DeliverablePackage> deliverables = list().stream().filter(predicate).collect(Collectors.toList());
        for (DeliverablePackage deliverable : deliverables) {
            // A deliverable can have several references, if it's installed using
            // the registry or directly from a remote URL.
            for (Deliverable ref : deliverable.references()) {
                ref.delete(deliverablesDir);
            }
        }
    }
}
