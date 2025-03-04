package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskRepository;

import java.util.concurrent.CountDownLatch;


@Singleton
public class TaskManagerMemory extends org.icij.datashare.asynctasks.TaskManagerMemory {

    @Inject
    public TaskManagerMemory(DatashareTaskFactory taskFactory, TaskRepository taskRepository, PropertiesProvider propertiesProvider) {
        this(taskFactory, taskRepository, propertiesProvider, new CountDownLatch(1));
    }

    TaskManagerMemory(DatashareTaskFactory taskFactory, TaskRepository taskRepository, PropertiesProvider propertiesProvider, CountDownLatch latch) {
        super(taskFactory, taskRepository, propertiesProvider, latch);
    }
}
