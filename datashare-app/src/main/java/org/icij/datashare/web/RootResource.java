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

<<<<<<< f0ef764713bb5ca1985d9f6e379e99d92dc174b7
    /**
     * gets the public (i.e. without user's information) datashare configuration parameters.
     *
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/config)
     */
=======
>>>>>>> [web] refactor root resources into a class
    @Get("config")
    public Map<String, Object> getPublicConfig() {
        return propertiesProvider.getFilteredProperties(".*Address.*", ".*Secret.*");
    }

<<<<<<< f0ef764713bb5ca1985d9f6e379e99d92dc174b7
    /**
     * Gets the versions (front/back/docker) of datashare.
     *
     * @return 200
     *
     * Example :
     * $(curl -i localhost:8080/version)
     */
=======
>>>>>>> [web] refactor root resources into a class
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
