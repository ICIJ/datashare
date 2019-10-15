package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.PropertiesProvider;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

@Prefix("/")
public class RootResource {
    private final PropertiesProvider propertiesProvider;

    @Inject
    public RootResource(PropertiesProvider propertiesProvider) {this.propertiesProvider = propertiesProvider;}

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
