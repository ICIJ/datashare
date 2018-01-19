package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import net.codestory.http.Configuration;
import net.codestory.http.WebServer;
import net.codestory.http.extensions.Extensions;
import net.codestory.http.misc.Env;

import static com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT;

public class WebApp {
    public static void main(String[] args) {
        new WebServer().configure(getConfiguration(new ProdServiceModule())).start();
    }

    static Configuration getConfiguration(final Module ioc) {
        Injector injector = Guice.createInjector(ioc);
        return routes -> routes
                .get("/", "Datashare REST API")
                .add(new TaskResource(injector.getInstance(TaskFactory.class), injector.getInstance(TaskManager.class)))
                .setExtensions(new Extensions() {
                    @Override
                    public ObjectMapper configureOrReplaceObjectMapper(ObjectMapper defaultObjectMapper, Env env) {
                        defaultObjectMapper.enable(ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
                        return defaultObjectMapper;
                    }
                });
    }
}