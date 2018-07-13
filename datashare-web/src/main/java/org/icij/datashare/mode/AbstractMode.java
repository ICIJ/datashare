package org.icij.datashare.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import net.codestory.http.Configuration;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.filters.Filter;
import net.codestory.http.injection.GuiceAdapter;
import net.codestory.http.misc.Env;
import net.codestory.http.routes.Routes;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.UserDataFilter;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;

public abstract class AbstractMode extends AbstractModule {
    protected final Properties properties;

    public AbstractMode(Properties properties) {
        this.properties = properties;
    }

    public AbstractMode(final Map<String, String> properties) {
        this.properties = new Properties();
        this.properties.putAll(properties);
    }

    public Configuration createWebConfiguration() {
        return routes -> addModeConfiguration(defaultRoutes(routes, new PropertiesProvider(this.properties)));
    }

    protected Routes addModeConfiguration(final Routes routes) {return routes;}

    private Routes defaultRoutes(final Routes routes, PropertiesProvider provider) {
        routes.setIocAdapter(new GuiceAdapter(this))
                .get("/config", provider.getFilteredProperties(".*Address.*", ".*Secret.*"))
                .bind(UserDataFilter.DATA_URI_PREFIX, Paths.get(provider.get("dataDir").orElse("/home/datashare/data")).toFile())
                .setExtensions(new Extensions() {
                    @Override
                    public ObjectMapper configureOrReplaceObjectMapper(ObjectMapper defaultObjectMapper, Env env) {
                        defaultObjectMapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                        return defaultObjectMapper;
                    }
                })
                .filter(Filter.class)
                .filter(new UserDataFilter());

        addModeConfiguration(routes);

        String cors = provider.get("cors").orElse("no-cors");
        if (!cors.equals("no-cors")) {
            routes.filter(new CorsFilter(cors));
        }
        return routes;
    }
}
