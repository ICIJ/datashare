package org.icij.datashare.kestra.plugin;

import io.micronaut.context.annotation.Factory;
import io.micronaut.guice.annotation.Guice;

@Factory
@Guice(modules = GreeterModule.class, classes = { DefaultGreeter.class, PirateGreeter.class })
public class GreeterApplication {
    public GreeterApplication() {
        System.out.println("GreeterApplication");
    }
}