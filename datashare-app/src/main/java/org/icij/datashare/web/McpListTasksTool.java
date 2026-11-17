package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.DESC;

public class McpListTasksTool implements McpTool {
    private static final McpArguments.IntArgument SIZE = new McpArguments.IntArgument("size", 20, 1, 100);
    private static final McpArguments.IntArgument FROM = new McpArguments.IntArgument("from", 0, 0, Integer.MAX_VALUE);
    private static final Comparator<Task<?>> NEWEST_FIRST =
            new Task.Comparator("createdAt", DESC).thenComparing(new Task.Comparator("id"));
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
        return "Lists the tasks you can see, newest first by creation date then by id, so pages keep their order " +
               "between calls.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        return taskResource.findVisibleTasks((User) context.currentUser(), taskFilters(arguments)).sorted(NEWEST_FIRST)
                           .skip(FROM.readFrom(arguments)).limit(SIZE.readFrom(arguments)).toList();
    }

    private static Map<String, String> taskFilters(JsonNode arguments) {
        String name = arguments.path("name").asText("");
        return name.isBlank() ? Map.of() : Map.of("name", name);
    }
}
