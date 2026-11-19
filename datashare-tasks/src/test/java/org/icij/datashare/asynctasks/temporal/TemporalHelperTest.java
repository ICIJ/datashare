package org.icij.datashare.asynctasks.temporal;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;
import static org.junit.Assert.assertThrows;

import io.temporal.failure.ApplicationFailure;
import java.util.Set;
import org.junit.Test;

public class TemporalHelperTest {
    @Test
    public void test_task_wrapper_returns_the_result() {
        assertThat(taskWrapper(() -> "result")).isEqualTo("result");
    }

    @Test
    public void test_task_wrapper_makes_unexpected_exceptions_non_retryable() {
        ApplicationFailure failure =
                assertThrows(ApplicationFailure.class, () -> taskWrapper(() -> {throw new IllegalStateException("boom");}));

        assertThat(failure.isNonRetryable()).isTrue();
    }

    @Test
    public void test_task_wrapper_keeps_retriable_exceptions() {
        assertThrows(IllegalStateException.class, () -> taskWrapper(() -> {throw new IllegalStateException("boom");},
                                                                    Set.of(IllegalStateException.class)));
    }

    @Test
    public void test_task_wrapper_does_not_make_interruption_non_retryable() {
        // what a task interrupted by the worker shutdown throws
        assertThrows(InterruptedException.class, () -> taskWrapper(rethrowFunction((String arg) -> {
            throw new InterruptedException("sleep interrupted");
        }), "arg"));
    }

    @Test
    public void test_task_wrapper_does_not_make_wrapped_interruption_non_retryable() {
        // Redisson and the Elasticsearch client re-interrupt the thread and wrap the InterruptedException
        RuntimeException wrapped = new RuntimeException("client interrupted", new InterruptedException());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> taskWrapper(() -> {throw wrapped;}));

        assertThat(thrown).isSameAs(wrapped);
    }
}
