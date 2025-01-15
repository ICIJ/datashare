package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;

import java.util.concurrent.CountDownLatch;


@Singleton
public class TaskManagerMemory extends org.icij.datashare.asynctasks.TaskManagerMemory {

    @Inject
    public TaskManagerMemory(DatashareTaskFactory taskFactory, PropertiesProvider propertiesProvider) {
        this(taskFactory, propertiesProvider, new CountDownLatch(1));
    }

    TaskManagerMemory(DatashareTaskFactory taskFactory, PropertiesProvider propertiesProvider, CountDownLatch latch) {
        super(taskFactory, propertiesProvider, new TaskRepositoryMemory(), latch);
    }
}
