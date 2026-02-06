package org.icij.datashare.asynctasks.temporal;

import static java.lang.Character.toUpperCase;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.EXECUTION_STATUS_ATTRIBUTE;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.USER_CUSTOM_ATTRIBUTE;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOW_TYPE_ATTRIBUTE;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.user.User;

public class TemporalQueryBuilder {
    private static final Pattern SUPPORTED_NAME_QUERY_PATTERN = Pattern.compile("^[\\w][\\.\\w]*[\\.\\*]?$");

    public static String buildFromFilters(TaskFilters filters) {
        String query = null;
        if (!filters.isEmpty()) {
            // We don't handle args with are filtered afterward because we can't store them as search attributes
            ArrayList<String> statements = new ArrayList<>(3);
            Optional.ofNullable(fromStates(filters.getStates())).ifPresent(statements::add);
            Optional.ofNullable(fromName(filters.getName())).ifPresent(statements::add);
            Optional.ofNullable(fromUser(filters.getUser())).ifPresent(statements::add);
            if (!statements.isEmpty()) {
                query = String.join(" AND ", statements.stream().map(s -> "( " + s + " )").toList());
            }
        }
        return query;
    }

    protected static String fromStates(Set<Task.State> states) {
        if (states == null || states.isEmpty()) {
            return null;
        }
        List<String> statuses = states.stream()
            .sorted()
            .flatMap(TemporalHelper::asWorkflowExecutionStatus)
            .distinct()
            .map(TemporalQueryBuilder::statusLabel)
            .toList();
        if (statuses.size() == 1) {
            return EXECUTION_STATUS_ATTRIBUTE.getName() + " = " + statuses.stream().iterator().next();
        }
        return String.join(
            " OR ",
            statuses.stream().map(s -> EXECUTION_STATUS_ATTRIBUTE.getName() + "= '" + s + "'").toList()
        );
    }

    protected static String fromName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String query = WORKFLOW_TYPE_ATTRIBUTE.getName();
        if (!SUPPORTED_NAME_QUERY_PATTERN.matcher(name).matches()) {
            String message = "invalid pattern " + name + " with Temporal, exact match or prefix queries are supported. "
                + " Test your name query against the " + SUPPORTED_NAME_QUERY_PATTERN.pattern() + " pattern to ensure"
                + " compatibility";
            throw new RuntimeException(message);
        }
        if (name.endsWith(".*")) {
            query += " STARTS_WITH " + "'" + name.substring(0, name.length() - 2) + "'";
        } else {
            query += " = " + "'" + name + "'";
        }
        return query;
    }

    protected static String fromUser(User user) {
        if (user == null) {
            return null;
        }
        return USER_CUSTOM_ATTRIBUTE.getName() + " = '" + user.getId() + "'";
    }

    private static String statusLabel(WorkflowExecutionStatus status) {
        String[] split = status.name()
            .replace("WORKFLOW_EXECUTION_STATUS_", "")
            .toLowerCase()
            .split("_");
        return stream(split)
            .map(s -> toUpperCase(s.charAt(0)) + s.substring(1))
            .collect(joining());
    }
}