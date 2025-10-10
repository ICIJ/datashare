package org.icij.datashare.kestra.plugin;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.junit.jupiter.api.Test;

/**
 * This test will only test the main task, this allow you to send any input
 * parameters to your task and test the returning behaviour easily.
 */
@MicronautTest(startApplication = false, packages = {"org.icij.datashare.kestra.plugin"}, application = DummyGuiceFactory.class)
class IFaceInjectionTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void testRun() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("path", "some_path")));
        ((DefaultRunContext) runContext).getApplicationContext().getBean(AmqpInterlocutor.class);
    }
}
