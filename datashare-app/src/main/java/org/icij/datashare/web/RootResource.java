package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR;

@Prefix("/")
public class RootResource {
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
    @Get("testapp/")
    public String getHome(Context context) {
        Map<String, Object> page = context.site().getPages().stream().filter(m -> "index".equals(m.get("name"))).findFirst().orElse(new HashMap<>());
        return propertiesProvider.get(PLUGINS_DIR).isPresent() ?
                new PluginService().addPlugins((String) page.get("content"), propertiesProvider.getProperties().getProperty(PLUGINS_DIR)):
                (String) page.get("content");
    }

    /**
     * gets the public (i.e. without user's information) datashare configuration parameters.
     *
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/config)
     */
    @Get("config")
    public Map<String, Object> getPublicConfig() {
        return propertiesProvider.getFilteredProperties(".*Address.*", ".*Secret.*");
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
