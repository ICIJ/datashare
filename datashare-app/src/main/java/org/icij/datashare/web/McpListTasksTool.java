package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.user.User;
import java.io.IOException;
import java.util.Map;

public class McpListTasksTool implements McpTool {
    private final TaskResource taskResource;

    @Inject
    public McpListTasksTool(TaskResource taskResource) {
        this.taskResource = taskResource;
    }

    @Override
    public String name() {
        return "list_tasks";
    }

    @Override
    public String description() {
        return "Lists the tasks you can see.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        return taskResource.findVisibleTasks((User) context.currentUser(), taskFilters(arguments)).toList();
    }

    private static Map<String, String> taskFilters(JsonNode arguments) {
        String name = arguments.path("name").asText("");
        return name.isBlank() ? Map.of() : Map.of("name", name);
    }
}
