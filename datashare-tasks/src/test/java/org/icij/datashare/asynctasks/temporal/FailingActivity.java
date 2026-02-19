package org.icij.datashare.asynctasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Map;

@ActivityInterface
public interface FailingActivity {
    @ActivityMethod(name = "failing")
    void failing(Map<String, Object> args);
}
