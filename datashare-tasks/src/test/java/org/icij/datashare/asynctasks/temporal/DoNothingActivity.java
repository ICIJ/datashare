package org.icij.datashare.asynctasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Map;

@ActivityInterface
public interface DoNothingActivity {
    @ActivityMethod(name = "org.icij.datashare.asynctasks.temporal.DoNothingTask")
    String run(Map<String, Object> args) throws Exception;
}
