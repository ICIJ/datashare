package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.PropertiesProvider;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFactory;


@Singleton
public class TaskManagerMemory extends org.icij.datashare.asynctasks.TaskManagerMemory implements DatashareTaskManager {

    @Inject
    public TaskManagerMemory(BlockingQueue<Task<?>> taskQueue, DatashareTaskFactory taskFactory, PropertiesProvider propertiesProvider) {
        this(taskQueue, taskFactory, propertiesProvider, new CountDownLatch(1));
    }

   TaskManagerMemory(BlockingQueue<Task<?>> taskQueue, DatashareTaskFactory taskFactory, PropertiesProvider propertiesProvider, CountDownLatch latch) {
        super(taskQueue, taskFactory, propertiesProvider, latch);
    }
}
