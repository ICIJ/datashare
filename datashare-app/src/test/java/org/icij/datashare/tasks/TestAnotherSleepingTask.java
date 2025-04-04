package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;

@TaskGroup(TaskGroupType.Test)
public class TestAnotherSleepingTask extends TestSleepingTask {
    public TestAnotherSleepingTask(int value) {
        super(value);
    }
}
