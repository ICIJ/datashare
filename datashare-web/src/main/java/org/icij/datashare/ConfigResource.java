package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.session.HashMapUser;

import java.util.Map;

@Prefix("/api")
public class ConfigResource {
    private PropertiesProvider provider;

    @Inject
    public ConfigResource(PropertiesProvider provider) {
        this.provider = provider;
    }

    @Get("/config")
    public Map<String, Object> getConfig(Context context) {
        Map<String, Object> filteredProperties = provider.getFilteredProperties(".*Address.*", ".*Secret.*");
        filteredProperties.put("userIndices", ((HashMapUser)context.currentUser()).getIndices());
        return filteredProperties;
    }
}
