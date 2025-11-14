package org.icij.datashare;

import static org.fest.assertions.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.junit.Test;

public class ModeTest {

    @Test
    public void debugCommonModeInjectionTestMode() throws IOException {
        // This test will in more obvious way than other if something goes wrong with injection, allowing to debug
        String[] args = {"--mode", "CLI"};
        DatashareCli cli = new DatashareCli().parseArguments(args);

        Map<String, Object> props = Map.of(
            "dataSourceUrl", "jdbc:postgresql://postgres:5432/?user=admin&password=admin",
            "mode", Mode.CLI.name()
        );
        cli.properties.putAll(props);
        try (CommonMode mode = CommonMode.create(cli.properties)) {
            assertThat(mode.get(DatashareTaskFactory.class)).isInstanceOf(DatashareTaskFactory.class);
//            assertThat(mode.get(TaskManager.class)).isInstanceOf(TaskManagerMemory.class);
//            assertThat(mode.get(TaskFactory.class)).isInstanceOf(DatashareTaskFactory.class);
        }
    }
}
