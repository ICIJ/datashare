package org.icij.datashare.mode;

import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.tasks.TaskManagerMemory;

import java.util.Properties;

public class CliMode extends CommonMode {
    CliMode(Properties properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        super.configure();

        bind(TaskManager.class).to(TaskManagerMemory.class).asEagerSingleton();
        configurePersistence();
    }
}
