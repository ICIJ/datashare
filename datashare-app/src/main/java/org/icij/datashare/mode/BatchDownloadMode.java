package org.icij.datashare.mode;

import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerRedis;

import java.util.Properties;

public class BatchDownloadMode extends CliMode {
    BatchDownloadMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        bind(TaskManager.class).toInstance(new TaskManagerRedis(propertiesProvider));
    }
}
