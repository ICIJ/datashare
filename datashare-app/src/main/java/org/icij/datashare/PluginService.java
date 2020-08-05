package org.icij.datashare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static org.icij.datashare.cli.DatashareCliOptions.PLUGIN_DELETE_OPT;

@Singleton
public class PluginService extends DeliverableService<Plugin> {
    public static final String DEFAULT_PLUGIN_REGISTRY_FILENAME = "plugins.json";
    public static final String PLUGINS_BASE_URL = "/plugins";

    public PluginService() { this(Paths.get("." + PLUGINS_BASE_URL));}

    @Inject
    public PluginService(PropertiesProvider propertiesProvider) {
        this(Paths.get(propertiesProvider.get(PropertiesProvider.PLUGINS_DIR).orElse("." + PLUGINS_BASE_URL)));
    }

    public PluginService(Path pluginsDir) {
        this(pluginsDir, ClassLoader.getSystemResourceAsStream(DEFAULT_PLUGIN_REGISTRY_FILENAME));
    }

    public PluginService(Path pluginsDir, InputStream inputStream) {
        super(pluginsDir, inputStream);
    }

    public void deleteFromCli(Properties properties) throws IOException {
        try {
            delete(properties.getProperty(PLUGIN_DELETE_OPT)); // plugin with id
        } catch (DeliverableRegistry.UnknownDeliverableException not_a_plugin) {
            delete(Paths.get(properties.getProperty(PLUGIN_DELETE_OPT))); // from base dir
        }
    }

    public void downloadAndInstallFromCli(String pluginIdOrUrlOrFile) throws IOException {
        try {
            downloadAndInstall(pluginIdOrUrlOrFile); // plugin with id
        } catch (DeliverableRegistry.UnknownDeliverableException not_a_plugin) {
            try {
                URL pluginUrl = new URL(pluginIdOrUrlOrFile);
                downloadAndInstall(pluginUrl); // from url
            } catch (MalformedURLException not_url) {
                new Plugin(null).install(Paths.get(pluginIdOrUrlOrFile).toFile(), extensionsDir); // from file
            }
        }
    }

    @Override
    Plugin newDeliverable(URL url) { return new Plugin(url);}

    @Override
    DeliverableRegistry<Plugin> getRegistry(InputStream pluginJsonContent) {
        try {
            return new ObjectMapper().readValue(pluginJsonContent, new TypeReference<DeliverableRegistry<Plugin>>() {});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void delete(String pluginId) throws IOException {
        delete(deliverableRegistry.get(pluginId).getBaseDirectory());
    }

    public void delete(Path pluginBaseDirectory) throws IOException {
        Path pluginDirectory = extensionsDir.resolve(pluginBaseDirectory);
        logger.info("removing plugin base directory {}", pluginDirectory);
        FileUtils.deleteDirectory(pluginDirectory.toFile());
    }

    public String addPlugins(String stringContent, List<String> userProjects) {
        File[] dirs = ofNullable(extensionsDir.toFile().listFiles(File::isDirectory)).
                orElseThrow(() -> new IllegalStateException("invalid path for plugins: " + extensionsDir));
        String scriptsString = stream(dirs).
                map(d -> projectFilter(d.toPath(), userProjects)).filter(Objects::nonNull).
                map(this::getPluginUrl).filter(Objects::nonNull).
                map(s -> "<script src=\"" + s + "\"></script>").collect(joining());
        String cssString = stream(dirs).
                map(d -> getCssPluginUrl(d.toPath())).filter(Objects::nonNull).
                map(s -> "<link rel=\"stylesheet\" href=\"" + s + "\">").collect(joining());
        return stringContent.
                replace("</body>", scriptsString + "</body>").
                replace("</head>", cssString + "</head>");
    }

    String getPluginUrl(Path pluginDir) {
        Path packageJson = pluginDir.resolve("package.json");
        if (packageJson.toFile().isFile()) {
            Path pluginMain = getPluginProperty(packageJson, "main");
            if (pluginMain == null) return null;
            logger.info("detected plugin <{}> with package.json", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(pluginDir, pluginMain).toString();
        }
        Path indexJs = pluginDir.resolve("index.js");
        if (indexJs.toFile().isFile()) {
            logger.info("detected plugin <{}> with index.js", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(pluginDir, indexJs).toString();
        }
        return null;
    }

    Path projectFilter(Path pluginDir, List<String> projects) {
        try {
            Path packageJson = pluginDir.resolve("package.json");
            if (packageJson.toFile().isFile()) {
                Map<String, Object> packageMap = new ObjectMapper().readValue(packageJson.toFile(), new TypeReference<HashMap<String, Object>>() {});
                if (packageMap.containsKey("private")) {
                    if (!Boolean.parseBoolean((packageMap.get("private").toString()))) {
                        return pluginDir;
                    }
                    if (packageMap.containsKey("datashare")) {
                        LinkedHashMap datashareMap = (LinkedHashMap) packageMap.get("datashare");
                        List<String> pluginProjects = ofNullable((List<String>) datashareMap.get("projects")).orElse(Collections.emptyList());
                        return !Collections.disjoint(pluginProjects, projects) ? pluginDir : null;
                    }
                    return null;
                }
            }
            return pluginDir;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    String getCssPluginUrl(Path pluginDir) {
        Path packageJson = pluginDir.resolve("package.json");
        if (packageJson.toFile().isFile()) {
            Path pluginMain = getPluginProperty(packageJson, "style");
            if (pluginMain == null) return null;
            logger.info("detected css plugin <{}> with package.json", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(pluginDir, pluginMain).toString();
        }
        return null;
    }

    private Path relativeToPlugins(Path pluginDir, Path pluginMain) {
        return Paths.get(PLUGINS_BASE_URL).resolve(pluginDir.getParent().relativize(pluginMain));
    }

    private Path getPluginProperty(Path packageJson, String property) {
        try {
            Map<String, Object> packageJsonMap = new ObjectMapper().readValue(packageJson.toFile(), new TypeReference<HashMap<String, Object>>() {});
            String value = (String) packageJsonMap.get(property);
            return value == null ? null : packageJson.getParent().resolve(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
