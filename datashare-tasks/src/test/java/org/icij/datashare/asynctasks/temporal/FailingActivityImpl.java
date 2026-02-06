package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

public class FailingActivityImpl implements FailingActivity {
    @Override
    public void failing() {
        taskWrapper(() -> {
            throw new RuntimeException("this is a failure");
        });
    }
}
