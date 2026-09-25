package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFactoryHelper;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.Callable;

import static org.fest.assertions.Assertions.assertThat;

public class DatashareTaskFactoryTest {
    @Test
    public void test_structured_entity_extraction_task_is_created_by_reflection() throws Exception {
        DatashareTaskFactory factory =
                CommonMode.create(Map.of("mode", Mode.LOCAL.name())).get(DatashareTaskFactory.class);
        String name = StructuredEntityExtractionTask.class.getName();

        Callable<?> callable = TaskFactoryHelper.createTaskCallable(factory, name,
                                                                   new Task<>(name, User.local(), Map.of()),
                                                                   p -> null);

        assertThat(callable).isInstanceOf(StructuredEntityExtractionTask.class);
    }
}
