package org.icij.datashare.kestra.plugin;

import com.google.inject.name.Named;
import io.micronaut.guice.annotation.Guice;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest(startApplication = false, packages = {"org.icij.datashare.kestra.plugin"}, application = GreeterApplication.class)
class InjectionTest {

    @Inject
    @Named("pirate")
    Greeter greeter;

    @Test
    void qualifiedBeanFromContextGetBean() {
        assertEquals("Ahoy", greeter.hello());
    }
}