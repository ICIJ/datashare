package org.icij.datashare.mode;

import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerMemory;

import java.util.Properties;

public class BatchSearchMode extends CliMode {
    BatchSearchMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        bind(TaskManager.class).toInstance(new TaskManagerMemory(propertiesProvider));
    }
}
