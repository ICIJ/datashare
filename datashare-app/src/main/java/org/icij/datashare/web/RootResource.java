package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

import static org.apache.commons.io.IOUtils.copy;
import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR;

@Prefix("/")
public class RootResource {
    public static final String INDEX_HTML = "index.html";
    private final PropertiesProvider propertiesProvider;

    @Inject
    public RootResource(PropertiesProvider propertiesProvider) {this.propertiesProvider = propertiesProvider;}

    /**
     * gets the root of the front-end app ie: ./app/index.html
     *
     * if pluginsDir is set, it will add in the index the tag <script src="plugins/my_plugin/index.js"></script>
     * else it will return the index.html content as is
     *
     * @return the content of index.html file
     */
    @Get()
    public String getRoot(Context context) throws IOException {
        Path index = new File(context.env().workingDir(), context.env().appFolder()).toPath().resolve(INDEX_HTML);
        String content;
        if (context.env().classPath() && !index.toFile().isFile()) {
            StringWriter writer = new StringWriter();
            copy(getClass().getResourceAsStream("/" + context.env().appFolder() + "/" + INDEX_HTML), writer, Charset.defaultCharset());
            content = writer.toString();
        } else {
            content = new String(Files.readAllBytes(index), Charset.defaultCharset());
        }
        return propertiesProvider.get(PLUGINS_DIR).isPresent() ?
                new PluginService().addPlugins(content, Paths.get(propertiesProvider.getProperties().getProperty(PLUGINS_DIR))):
                content;
    }

    /**
     * gets the public (i.e. without user's information) datashare settings parameters.
     *
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/settings)
     */
    @Get("settings")
    public Map<String, Object> getPublicSettings() {
        return propertiesProvider.getFilteredProperties(".*Address.*", ".*Secret.*", ".*Url.*");
    }

    /**
     * Gets the versions (front/back/docker) of datashare.
     *
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/version)
     */
    @Get("version")
    public Properties getVersion() {
        try {
            Properties properties = new Properties();
            properties.load(getClass().getResourceAsStream("/git.properties"));
            return properties;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
