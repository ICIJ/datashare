package org.icij.datashare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

public class PluginService {
    Logger logger = LoggerFactory.getLogger(getClass());
    public static final String PLUGINS_BASE_URL = "/plugins";

    public String addPlugins(String stringContent, Path pluginsDir) {
        File[] dirs = ofNullable(pluginsDir.toFile().listFiles(File::isDirectory)).
                orElseThrow(() -> new IllegalStateException("invalid path for plugins: " + pluginsDir));
        String scriptsString = stream(dirs).
                map(d -> getPluginUrl(d.toPath())).filter(Objects::nonNull).
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
            return relativeToPlugins(pluginMain).toString();
        }
        Path indexJs = pluginDir.resolve("index.js");
        if (indexJs.toFile().isFile()) {
            logger.info("detected plugin <{}> with index.js", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(indexJs).toString();
        }
        return null;
    }

    String getCssPluginUrl(Path pluginDir) {
        Path packageJson = pluginDir.resolve("package.json");
        if (packageJson.toFile().isFile()) {
            Path pluginMain = getPluginProperty(packageJson, "style");
            if (pluginMain == null) return null;
            logger.info("detected css plugin <{}> with package.json", pluginDir.getParent().relativize(pluginDir));
            return relativeToPlugins(pluginMain).toString();
        }
        return null;
    }

    private Path relativeToPlugins(Path pluginMain) {
        return Paths.get(PLUGINS_BASE_URL).resolve(pluginMain.subpath(pluginMain.getNameCount() - 2, pluginMain.getNameCount()));
    }

    private Path getPluginProperty(Path packageJson, String property) {
        try {
            Map<String, String> packageJsonMap = new ObjectMapper().readValue(packageJson.toFile(), new TypeReference<HashMap<String, String>>() {});
            String value = packageJsonMap.get(property);
            return value == null ? null: packageJson.getParent().resolve(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
