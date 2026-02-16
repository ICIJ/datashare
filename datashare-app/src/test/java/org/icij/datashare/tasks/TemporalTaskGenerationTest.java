package org.icij.datashare.tasks;


import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.mode.CommonMode;
import org.junit.Test;

public class TemporalTaskGenerationTest {

    @Test
    public void test_should_annotate_all_factory_classes() {
        // Let's check we didn't forget any decorator
        DatashareTaskFactory factory = CommonMode.create(Map.of("mode", Mode.LOCAL.name()))
            .get(DatashareTaskFactory.class);

        Arrays.stream(factory.getClass().getMethods()).forEach(m -> {
            Class<?> returnType = m.getReturnType();
            if (returnType.getAnnotationsByType(TemporalSingleActivityWorkflow.class).length == 0) {
                throw new AssertionError("missing " + TemporalSingleActivityWorkflow.class.getSimpleName() + " annotation for " + returnType);
            }
        });
    }

}
