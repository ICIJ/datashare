package org.icij.datashare.asynctasks.temporal;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.user.User;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.temporal.api.enums.v1.WorkflowExecutionStatus.*;
import static org.icij.datashare.asynctasks.temporal.TemporalInterlocutor.USER_CUSTOM_ATTRIBUTE;

public class TemporalHelper {

    private static final DefaultDataConverter defaultDataConverter = DefaultDataConverter.newDefaultInstance();


    public static Task.State asTaskState(WorkflowExecutionStatus status) {
        return switch (status) {
            case WORKFLOW_EXECUTION_STATUS_UNSPECIFIED ->
                throw new RuntimeException("unknown temporal workflow state " + status);
            // Could be queued but temporal as no such state...
            case WORKFLOW_EXECUTION_STATUS_RUNNING -> Task.State.RUNNING;
            case WORKFLOW_EXECUTION_STATUS_COMPLETED -> Task.State.DONE;
            case WORKFLOW_EXECUTION_STATUS_FAILED -> Task.State.ERROR;
            case WORKFLOW_EXECUTION_STATUS_CANCELED -> Task.State.CANCELLED;
            case WORKFLOW_EXECUTION_STATUS_TERMINATED -> Task.State.CANCELLED;
            case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW ->
                throw new RuntimeException("continue as new is not currently supported " + status);
            case WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> Task.State.ERROR;
            case UNRECOGNIZED -> throw new RuntimeException("unknown temporal workflow state " + status);
        };
    }

    public static Stream<WorkflowExecutionStatus> asWorkflowExecutionStatus(Task.State state) {
        return switch (state) {
            case CREATED -> Stream.of(WORKFLOW_EXECUTION_STATUS_CANCELED, WORKFLOW_EXECUTION_STATUS_TERMINATED);
            // This is merged with running in Temporal
            case QUEUED -> Stream.of(WORKFLOW_EXECUTION_STATUS_RUNNING);
            case RUNNING -> Stream.of(WORKFLOW_EXECUTION_STATUS_RUNNING);
            case CANCELLED -> Stream.of(WORKFLOW_EXECUTION_STATUS_CANCELED, WORKFLOW_EXECUTION_STATUS_TERMINATED);
            case ERROR -> Stream.of(WORKFLOW_EXECUTION_STATUS_FAILED, WORKFLOW_EXECUTION_STATUS_TIMED_OUT);
            case DONE -> Stream.of(WORKFLOW_EXECUTION_STATUS_COMPLETED);
        };
    }

    public static Predicate<WorkflowExecutionInfo> asExecInfoFilter(TaskFilters filters) {
        return execInfo -> {
            if (!filters.byName(execInfo.getType().getName())) {
                return false;
            }
            if (!filters.byState(asTaskState(execInfo.getStatus()))) {
                return false;
            }
            User user = Optional.ofNullable(execInfo.getSearchAttributes().getIndexedFieldsOrDefault(USER_CUSTOM_ATTRIBUTE.getName(), null))
                    .map(userId -> new User (defaultDataConverter.fromPayload(userId, String.class, String.class))).orElse(null);

            return filters.byUser(user);
        };
    }

    protected static <P, R> R taskWrapper(Function<P, R> taskFn, P payload, Set<Class<? extends Exception>> retriables) {
        try {
            return taskFn.apply(payload);
        } catch (Exception e) {
            if (retriables.stream().anyMatch(r -> r.isInstance(e))) {
                throw e;
            }
            throw ApplicationFailure.newNonRetryableFailureWithCause("Non retryable failure occurred", e.getMessage(), e);
        }
    }

    protected static <P, R> R taskWrapper(Function<P, R> taskFn, P payload) {
        return taskWrapper(taskFn, payload, Set.of());
    }

    protected static <R> R taskWrapper(Supplier<R> taskSupplier,
                                                            Set<Class<? extends Exception>> retriables) {
        return taskWrapper((t) -> taskSupplier.get(), null, retriables);
    }

    protected static <R> R taskWrapper(Supplier<R> taskSupplier) {
        return taskWrapper((t) -> taskSupplier.get(), null, Set.of());
    }
}
