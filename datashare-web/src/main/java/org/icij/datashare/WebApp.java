package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import net.codestory.http.Configuration;
import net.codestory.http.WebServer;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.misc.Env;

import java.util.Properties;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;

public class WebApp {
    public static void main(String[] args) {
        start(null);
    }

    static Configuration getConfiguration(final Module ioc) {
        Injector injector = Guice.createInjector(ioc);
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

    public static void start(Properties properties) {
        new WebServer().configure(getConfiguration(new ProdServiceModule(properties))).start();
    }
}