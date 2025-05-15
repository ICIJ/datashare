package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.icij.datashare.user.User;
import org.junit.Test;

public class TaskFilterTest {
    static final Task t1 = new Task("someTask", User.local(), Map.of());
    static Task t2 = new Task("someOtherTask", null, Map.of("nested", Map.of("attribute","nestedvalue")));
    static {
        t2.setState(Task.State.DONE);
    }
    static List<Task> tasks = List.of(t1, t2);

    @Test
    public void test_task_empty_filters() {
        // Given
        TaskFilters filters = TaskFilters.empty();
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(tasks);
    }

    @Test
    public void test_task_empty_args() {
        // Given
        TaskFilters filters = TaskFilters.empty().withArgs(List.of());
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(tasks);
    }

    @Test
    public void test_task_empty_states() {
        // Given
        TaskFilters filters = TaskFilters.empty().withStates(Set.of());
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(tasks);
    }

    @Test
    public void test_task_filters_by_names() {
        // Given
        TaskFilters filters = TaskFilters.empty().withNames("someT.*");
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of(t1));
    }

    @Test
    public void test_task_filters_by_names_with_regex_pattern() {
        // Given
        TaskFilters filters = TaskFilters.empty().withNames(".*some|someOther.*");
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of(t1, t2));
    }

    @Test
    public void test_task_filters_by_user() {
        // Given
        TaskFilters filters = TaskFilters.empty().withUser(User.local());
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of(t1));
    }

    @Test
    public void test_task_filters_by_args_and_starting_pattern() {
        // Given
        TaskFilters.ArgsFilter argsFilter = new TaskFilters.ArgsFilter("nested.attribute", "nestedv.*");
        TaskFilters filters = TaskFilters.empty().withArgs(List.of(argsFilter));
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of(t2));
    }

    @Test
    public void test_task_filters_by_args_and_starting_pattern_without_matches() {
        // Given
        TaskFilters.ArgsFilter argsFilter = new TaskFilters.ArgsFilter("nested.attribute", "foo.*");
        TaskFilters filters = TaskFilters.empty().withArgs(List.of(argsFilter));
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of());
    }

    @Test
    public void test_task_filters_by_args_and_containing_pattern() {
        // Given
        TaskFilters.ArgsFilter argsFilter = new TaskFilters.ArgsFilter("nested.attribute", ".*ed.*");
        TaskFilters filters = TaskFilters.empty().withArgs(List.of(argsFilter));
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of(t2));
    }


    @Test
    public void test_task_filters_by_args_and_containing_pattern_without_matches() {
        // Given
        TaskFilters.ArgsFilter argsFilter = new TaskFilters.ArgsFilter("nested.attribute", ".*foo.*");
        TaskFilters filters = TaskFilters.empty().withArgs(List.of(argsFilter));
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of());
    }

    @Test
    public void test_task_filters_by_state() {
        // Given
        TaskFilters filters = TaskFilters.empty().withStates(Set.of(Task.State.DONE));
        // When
        List<Task> filtered = tasks.stream().filter(filters::filter).toList();
        // Then
        assertThat(filtered).isEqualTo(List.of(t2));
    }
}
