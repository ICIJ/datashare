package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import org.icij.datashare.asynctasks.Task;


@Singleton
public class TaskManagerMemory extends org.icij.datashare.asynctasks.TaskManagerMemory {

    @Inject
    public TaskManagerMemory(BlockingQueue<Task<?>> taskQueue, DatashareTaskFactory taskFactory) {
        this(taskQueue, taskFactory, new CountDownLatch(1));
    }

    TaskManagerMemory(BlockingQueue<Task<?>> taskQueue, DatashareTaskFactory taskFactory, CountDownLatch latch) {
        super(taskQueue, taskFactory, latch);
    }
}
