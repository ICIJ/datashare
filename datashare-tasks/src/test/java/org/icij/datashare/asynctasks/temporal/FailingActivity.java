package org.icij.datashare.asynctasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface FailingActivity {
    @ActivityMethod(name = "failing")
    void failing();
}
