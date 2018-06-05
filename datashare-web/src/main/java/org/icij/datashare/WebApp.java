package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
import net.codestory.http.Configuration;
import net.codestory.http.WebServer;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.misc.Env;
import org.icij.datashare.session.OAuth2CookieAuthFilter;

import java.util.Properties;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;
import static java.lang.Boolean.parseBoolean;

public class WebApp {
    public static void main(String[] args) {
        start(null);
    }

    static Configuration getConfiguration(final Injector injector) {
        if (parseBoolean(injector.getInstance(PropertiesProvider.class).get("auth").orElse("false"))) {
            return routes -> routes
                            .add(injector.getInstance(TaskResource.class))
                            .setExtensions(new Extensions() {
                                @Override
                                public ObjectMapper configureOrReplaceObjectMapper(ObjectMapper defaultObjectMapper, Env env) {
                                    defaultObjectMapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                                    return defaultObjectMapper;
                                }
                            }).filter(injector.getInstance(OAuth2CookieAuthFilter.class));
        } else {
            return routes -> routes
                    .add(injector.getInstance(TaskResource.class))
                    .setExtensions(new Extensions() {
                        @Override
                        public ObjectMapper configureOrReplaceObjectMapper(ObjectMapper defaultObjectMapper, Env env) {
                            defaultObjectMapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                            return defaultObjectMapper;
                        }
                    });
        }
    }

    public static void start(Properties properties) {
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(getConfiguration(Guice.createInjector(new ProdServiceModule(properties)))).start();
    }
}