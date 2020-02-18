package org.icij.datashare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR;

public class PluginService {
    public static final String PLUGINS_LINK = "plugins";

    public String addPlugins(String stringContent, Path pluginsDir) {
        File[] dirs = ofNullable(pluginsDir.toFile().listFiles(File::isDirectory)).
                orElseThrow(() -> new IllegalStateException("invalid path for plugins: " + pluginsDir));
        String scriptsString = stream(dirs).
                map(d -> getPluginUrl(d.toPath())).filter(Objects::nonNull).
                map(s -> "<script src=\"" + s + "\"></script>").collect(joining());
        return stringContent.replace("</body>", scriptsString + "</body>");
    }

    public static void createLinkToPlugins(Path pathToApp, Properties properties) throws IOException {
        Path pluginsDir = pathToApp.resolve(PLUGINS_LINK);
        if (properties.containsKey(PLUGINS_DIR)) {
            Path target = Paths.get(properties.getProperty(PLUGINS_DIR));
            if (target.toFile().isDirectory()) {
                pluginsDir.toFile().delete();
                Files.createSymbolicLink(pluginsDir, target);
            }
        }
    }

    String getPluginUrl(Path pluginDir) {
        Path packageJson = pluginDir.resolve("package.json");
        if (packageJson.toFile().isFile()) {
            Path pluginMain = getPluginMain(packageJson);
            return relativeToPlugins(pluginMain).toString();
        }
        Path indexJs = pluginDir.resolve("index.js");
        if (indexJs.toFile().isFile()) {
            return relativeToPlugins(indexJs).toString();
        }
        return null;
    }

    private Path relativeToPlugins(Path pluginMain) {
        return Paths.get("/" + PLUGINS_LINK).resolve(pluginMain.subpath(pluginMain.getNameCount() - 2, pluginMain.getNameCount()));
    }

    private Path getPluginMain(Path packageJson) {
        try {
            Map<String, String> packageJsonMap = new ObjectMapper().readValue(packageJson.toFile(), new TypeReference<HashMap<String, String>>() {});
            return packageJson.getParent().resolve(packageJsonMap.get("main"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
