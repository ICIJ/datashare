package org.icij.datashare.asynctasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Map;

@ActivityInterface
public interface HelloWorldActivity {
    @ActivityMethod(name = "hello_world")
    String helloWorld(Map<String, Object> args);
}
