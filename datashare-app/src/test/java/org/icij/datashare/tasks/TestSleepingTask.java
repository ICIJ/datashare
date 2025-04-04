package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;

import java.util.function.Function;

@TaskGroup(TaskGroupType.Test)
public class TestSleepingTask extends TestTask {
    public TestSleepingTask(int value) {
        super(value);
    }

    @Override
    public Integer call() throws Exception {
        callThread = Thread.currentThread();
        int ret = super.call();
        try {
            Thread.sleep(ret);
            return ret;
        } catch (InterruptedException iex) {
            throw new CancelException(this.requeue);
        }
    }
}

