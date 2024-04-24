package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.asynctasks.TaskView;

public class TaskViewEvent extends Event {
    public final TaskView<?> taskView;

    @JsonCreator
    public TaskViewEvent(@JsonProperty("taskView") TaskView<?> taskView) {
        this.taskView = taskView;
    }
}
