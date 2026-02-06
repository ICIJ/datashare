package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

public class HelloWorldActivityImpl implements HelloWorldActivity {

    @Override
    public String helloWorld() {
        return taskWrapper(() -> "hello world");
    }
}
