package org.icij.datashare.tasks;

import java.util.Optional;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;


@TaskGroup(TaskGroupType.Test)
public class TestSleepingTask extends TestTask {
    public TestSleepingTask(Integer value) {
        super(value);
    }

    @Override
    public Integer runTask() {
        callThread = Thread.currentThread();
        Integer ret = Optional.ofNullable(super.runTask()).orElse(Integer.MAX_VALUE);
        try {
            Thread.sleep(ret);
            return ret;
        } catch (InterruptedException iex) {
            throw new CancelException(this.requeue);
        }
    }
}

