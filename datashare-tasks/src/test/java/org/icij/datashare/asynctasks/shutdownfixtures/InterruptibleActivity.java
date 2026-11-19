package org.icij.datashare.asynctasks.shutdownfixtures;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Map;

@ActivityInterface
public interface InterruptibleActivity {
    @ActivityMethod(name = "interruptible")
    String interruptible(Map<String, Object> args);
}
