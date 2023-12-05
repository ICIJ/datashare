package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.tasks.TaskView;

public class TaskViewEvent extends Event {
    public final TaskView<?> taskView;

    @JsonCreator
    public TaskViewEvent(@JsonProperty("taskView") TaskView<?> taskView) {
        this.taskView = taskView;
    }
}
