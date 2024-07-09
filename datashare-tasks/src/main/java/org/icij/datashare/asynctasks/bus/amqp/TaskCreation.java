package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.icij.datashare.asynctasks.TaskView;

import java.io.IOException;
import java.util.Date;

@JsonDeserialize(using = TaskCreation.TaskCreationDeserializer.class)
public class TaskCreation extends Event {
    @JsonUnwrapped // to avoid nested object
    public final TaskView<?> taskView;

    public TaskCreation(TaskView<?> taskView) {
        this.taskView = taskView;
    }

    TaskCreation(Date creationDate, int ttl, TaskView<?> taskView) {
        super(creationDate, ttl);
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
            TaskView<?> taskView = oc.treeToValue(taskViewEventNode, TaskView.class);
            return new TaskCreation(new Date(taskViewEventNode.get("createdAt").asLong()),
                    taskViewEventNode.get("ttl").asInt(), taskView);
        }
    }
}
