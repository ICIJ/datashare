package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.icij.datashare.asynctasks.Task;

import java.io.IOException;
import java.util.Date;

@JsonDeserialize(using = TaskCreation.TaskCreationDeserializer.class)
public class TaskCreation extends Event {
    @JsonUnwrapped // to avoid nested object
    public final Task taskView;

    public TaskCreation(Task taskView) {
        this.taskView = taskView;
    }

    TaskCreation(Date creationDate, int retries, Task taskView) {
        super(creationDate, retries);
        this.taskView = taskView;
    }

    static class TaskCreationDeserializer extends StdDeserializer<TaskCreation> {
        protected TaskCreationDeserializer() {
            super((Class<?>) null);
        }
        protected TaskCreationDeserializer(Class<?> vc) {
            super(vc);
        }

        @Override
        public TaskCreation deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            ObjectCodec oc = p.getCodec();
            JsonNode taskViewEventNode = oc.readTree(p);
            Task taskView = oc.treeToValue(taskViewEventNode, Task.class);
            return new TaskCreation(new Date(taskViewEventNode.get("createdAt").asLong()),
                    taskViewEventNode.get("retries").asInt(), taskView);
        }
    }
}
