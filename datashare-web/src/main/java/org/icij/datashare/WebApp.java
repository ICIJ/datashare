package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
import net.codestory.http.Configuration;
import net.codestory.http.WebServer;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.filters.Filter;
import net.codestory.http.misc.Env;
import net.codestory.http.routes.Routes;

import java.util.Properties;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;

public class WebApp {
    public static void main(String[] args) {
        start(null);
    }

    static Configuration getConfiguration(final Injector injector) {
        Configuration configuration = routes -> {
            Routes rts = routes.get("/config",
                    injector.getInstance(PropertiesProvider.class).
                            getFilteredProperties(".*Address.*", ".*Secret.*"))
                    .add(injector.getInstance(TaskResource.class))
                    .add(injector.getInstance(SearchResource.class))
                    .setExtensions(new Extensions() {
                        @Override
                        public ObjectMapper configureOrReplaceObjectMapper(ObjectMapper defaultObjectMapper, Env env) {
                            defaultObjectMapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                            return defaultObjectMapper;
                        }
                    }).filter(injector.getInstance(Filter.class));

            String cors = injector.getInstance(PropertiesProvider.class).get("cors").orElse("no-cors");
            if (!cors.equals("no-cors")) {
                rts.filter(new CorsFilter(cors));
            }
        };
        return configuration;

    }

    public static void start(Properties properties) {
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(getConfiguration(Guice.createInjector(new ProdServiceModule(properties)))).start();
    }
}