package org.icij.datashare.web;

import net.codestory.http.Context;
import net.codestory.http.Request;
import net.codestory.http.errors.BadRequestException;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.tasks.TaskType;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.MockRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.Task.State.DONE;

@RunWith(MockitoJUnitRunner.class)
public class TaskFiltersTest {
    private final TaskResource taskResource = new TaskResource(null, null, null, null, null);

    @Test(expected = BadRequestException.class)
    public void test_task_filters_from_context_should_throw_for_unsupported_filter() {
        Request request = new MockRequest(Map.of("unknown", "field"));
        taskResource.taskFiltersFromContext(request.query(), null, null);
    }

    @Test
    public void test_task_filters_from_context_should_not_filter() {
        // Given
        Request request = new MockRequest(Map.of());
        // When
        TaskFilters filters = taskResource.taskFiltersFromContext(request.query(), null, null);

        // Then
        TaskFilters expectedFilters = new TaskFilters().withStates(Set.of()).withTypes(Set.of()).with();
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_names() {
        // Given
        Request request = new MockRequest(Map.of("name", "someTask|someOtherTask"));
        // When
        TaskFilters filters = taskResource.taskFiltersFromContext(request.query(), null, null);
        // Then
        TaskFilters expectedFilters = new TaskFilters().withStates(Set.of()).withTypes(Set.of()).with()
                .with("someTask|someOtherTask.*");
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_user() {
        // Given
        class MockUser extends User implements net.codestory.http.security.User {
            public MockUser() {
                super("some-id", "some-name", "", "", "{}");
            }
            @Override
            public String login() { return null; }
            @Override
            public String[] roles() { return new String[0];}
        }

        MockUser mockUser = new MockUser();
        Request request = new MockRequest(Map.of());
        Context ctx = new Context(request, null, null, null, null);
        ctx.setCurrentUser(mockUser);
        // When
        TaskFilters filters = taskResource.taskFiltersFromContext(ctx);
        // Then
        TaskFilters expectedFilters = new TaskFilters().withStates(Set.of()).withTypes(Set.of()).with()
                .with(mockUser);
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_args() {
        // Given
        Request request = new MockRequest(Map.of("args.nested.attribute", "someregex"));
        // When
        TaskFilters filters = taskResource.taskFiltersFromContext(request.query(), null, null);
        // Then
        TaskFilters expectedFilters = new TaskFilters().withStates(Set.of()).withTypes(Set.of())
                .with(new TaskFilters.ArgsFilter("nested.attribute", ".*someregex.*"));
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_state() {
        // Given
        Request request = new MockRequest(Map.of("state", "DONE"));
        // When
        TaskFilters filters = taskResource.taskFiltersFromContext(request.query(), null, null);
        // Then
        TaskFilters expectedFilters = new TaskFilters().withStates(Set.of(DONE)).withTypes(Set.of()).with();
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_type() {
        // Given
        Request request = new MockRequest(Map.of("type", "BATCH_SEARCH|BATCH_DOWNLOAD"));
        // When
        TaskFilters filters = taskResource.taskFiltersFromContext(request.query(), null, null);
        // Then
        TaskFilters expectedFilters = new TaskFilters().withStates(Set.of())
                .withTypes(Set.of(TaskType.BATCH_SEARCH, TaskType.BATCH_DOWNLOAD)).with();
        assertThat(filters).isEqualTo(expectedFilters);
    }

    @Test
    public void test_task_filters_from_context_should_filter_task_by_type_case_insensitive() {
        Request request = new MockRequest(Map.of("type", "Batch_Search|batch_download"));

        TaskFilters filters = taskResource.taskFiltersFromContext(request.query(), null, null);

        TaskFilters expectedFilters = new TaskFilters().withStates(Set.of())
                .withTypes(Set.of(TaskType.BATCH_SEARCH, TaskType.BATCH_DOWNLOAD)).with();
        assertThat(filters).isEqualTo(expectedFilters);
    }
}
