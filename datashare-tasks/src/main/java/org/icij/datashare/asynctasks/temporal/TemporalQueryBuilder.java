package org.icij.datashare.asynctasks.temporal;

import static java.lang.Character.toUpperCase;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.EXECUTION_STATUS_ATTRIBUTE;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.USER_CUSTOM_ATTRIBUTE;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.WORKFLOW_TYPE_ATTRIBUTE;

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
            Optional.ofNullable(fromNamePattern(filters.getName())).ifPresent(statements::add);
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

    protected static String fromNamePattern(String namePattern) {
        if (namePattern == null) {
            return null;
        }
        if (namePattern.isEmpty()) {
            throw new IllegalArgumentException("name pattern cannot be empty");
        }
        String[] parts = namePattern.split("\\|");
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("name pattern contains an empty alternative: '" + namePattern + "'");
            }
            String trimmed = part.trim();
            if (!SUPPORTED_NAME_QUERY_PATTERN.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("invalid pattern " + trimmed + " with Temporal, exact match or prefix queries are supported."
                    + " Test your name query against the " + SUPPORTED_NAME_QUERY_PATTERN.pattern() + " pattern to ensure compatibility");
            }
            if (trimmed.endsWith(".*")) {
                clauses.add(WORKFLOW_TYPE_ATTRIBUTE.getName() + " STARTS_WITH '" + trimmed.substring(0, trimmed.length() - 2) + "'");
            } else {
                clauses.add(WORKFLOW_TYPE_ATTRIBUTE.getName() + " = '" + trimmed + "'");
            }
        }
        return String.join(" OR ", clauses);
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