package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.Mode;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.HashMapUser;

import java.util.List;
import java.util.Map;

@Prefix("/api")
public class ConfigResource {
    private PropertiesProvider provider;

    @Inject
    public ConfigResource(PropertiesProvider provider) {
        this.provider = provider;
    }

    /**
     * gets the private datashare configuration with user's information
     *
     * @return 200 and the json config
     *
     * Example :
     * $(curl -i localhost:8080/api/config)
     */
    @Get("/config")
    public Map<String, Object> getConfig(Context context) {
        Map<String, Object> filteredProperties = provider.getFilteredProperties(".*Address.*", ".*Secret.*");
        HashMapUser user = (HashMapUser) context.currentUser();
        List<String> indices = user.getIndices();
        if (!provider.get("mode").orElse(Mode.LOCAL.toString()).equals(Mode.SERVER.toString())) {
            indices.add(0, user.defaultProject());
        }
        filteredProperties.put("userIndices", indices);
        return filteredProperties;
    }
}
