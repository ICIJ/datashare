package org.icij.datashare.db;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.TaskStateMetadata;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.user.User;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class JooqTaskRepositoryTest {
    @Rule
    public DbSetupRule dbRule;
    private final JooqTaskRepository repository;
    private static final List<DbSetupRule> rulesToClose = new ArrayList<>();

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        String postgresUrl = EnvUtils.resolveUri("postgres", "jdbc:postgresql://postgres/dstest?user=dstest&password=test");
        return asList(new Object[][] {
            {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
            {new DbSetupRule(postgresUrl)}
        });
    }

    @Test
    public void test_insert_with_null_id_should_throw() {
        Task<Serializable> task = new Task<>(null, "foo", User.local(), Map.of());
        assertThrows(IntegrityConstraintViolationException.class,
            () -> repository.insert(task, new Group(TaskGroupType.Test)));
    }

    @Test
    public void test_insert() throws IOException {
        Task<Serializable> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));

        repository.insert(foo, new Group(TaskGroupType.Test));

        Task<?> actual = repository.getTask(foo.getId());
        Group actualGroup = repository.getTaskGroup(foo.getId());
        assertThat(actual).isNotSameAs(foo); // not same instance
        assertThat(actual).isEqualTo(foo); // but equals as defined by Task
        assertThat(actual.getUser()).isEqualTo(User.local());
        assertThat(actualGroup.getId()).isEqualTo("Test");
    }

    @Test
    public void test_insert_should_throw_already_exists() throws IOException {
        // Given
        Task<Integer> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        // When
        repository.insert(foo, new Group(TaskGroupType.Test));
        // Then
        assertThrows(TaskAlreadyExists.class, () -> repository.insert(foo, new Group(TaskGroupType.Test)));
    }

    @Test
    public void test_update() throws Exception {
        Task<Integer> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));

        repository.insert(foo, new Group(TaskGroupType.Test));
        assertThat(repository.getTask(foo.id).getState()).isEqualTo(Task.State.CREATED);
        foo.setProgress(0.5);
        repository.update(foo);

        Task<?> actual = repository.getTask(foo.getId());
        Group actualGroup = repository.getTaskGroup(foo.getId());
        assertThat(actualGroup).isEqualTo(new Group(TaskGroupType.Test));
        assertThat(actual.getState()).isEqualTo(Task.State.RUNNING);
        assertThat(actual.getCompletedAt()).isNull();

        foo.setResult(new TaskResult<>(1));
        repository.update(foo);
        actual = repository.getTask(foo.getId());
        assertThat(actual.getState()).isEqualTo(Task.State.DONE);
        assertThat(actual.getCompletedAt()).isNotNull();
    }

    @Test
    public void test_update_should_throw_unknown_task() {
        // Given
        Task<Integer> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        // When/Then
        assertThrows(UnknownTask.class, () -> repository.update(foo));
    }


    @Test
    public void test_delete() throws IOException {
        Task<?> foo = new Task<>("foo", User.local(), Map.of());
        assertThat(assertThrows(UnknownTask.class, () -> repository.delete(foo.getId())));
        repository.insert(foo, new Group(TaskGroupType.Test));
        assertThat(repository.delete(foo.getId())).isEqualTo(foo);
    }

    @Test
    public void test_delete_should_throw_unknown_task() {
        assertThrows(UnknownTask.class, () -> repository.delete("someTask"));
    }

    @Test
    public void test_get_unknown_task_should_thrown_unknown_task_error() {
        assertThrows(UnknownTask.class, () -> repository.getTask("unknown"));
    }

    @Test
    public void test_get_result() throws Exception {
        Task<UriResult> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        repository.insert(foo, new Group(TaskGroupType.Test));

        TaskResult<UriResult> result = new TaskResult<>(new UriResult(new URI("file:///my/file"), 123));
        foo.setResult(result);
        repository.update(foo);
        assertThat(repository.getTask(foo.getId()).getError()).isNull();
        assertThat(repository.getTask(foo.getId()).getResult()).isEqualTo(result);
    }

    @Test
    public void test_get_error() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        repository.insert(foo, new Group(TaskGroupType.Test));

        foo.setError(new TaskError(new RuntimeException("boom")));
        repository.update(foo);
        assertThat(repository.getTask(foo.getId()).getResult()).isNull();
        assertThat(repository.getTask(foo.getId()).getError().getMessage()).isEqualTo("boom");
    }

    @Test
    public void test_get_tasks() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("user", User.local()));
        repository.insert(foo, new Group(TaskGroupType.Test));
        repository.insert(bar, new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty();

        List<Task<? extends Serializable>> tasks = repository.getTasks(filter).toList();

        assertThat(tasks.size()).isEqualTo(2);
        assertThat(tasks.stream().map(Task::getId).toList()).isEqualTo(List.of(foo.id, bar.id));
    }


    @Test
    public void test_get_tasks_with_names_filter() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("user", User.local()));
        repository.insert(foo, new Group(TaskGroupType.Test));
        repository.insert(bar, new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty().withNames("foo");

        List<Task<? extends Serializable>> tasks = repository.getTasks(filter).toList();

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).id).isEqualTo(foo.id);
    }

    @Test
    public void test_get_tasks_with_status_filter() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("user", User.local()));
        // Bar must have a result to be considered as "DONE"
        bar.setResult(new TaskResult<>("1"));
        repository.insert(foo, new Group(TaskGroupType.Test));
        repository.insert(bar, new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty().withStates(Task.State.FINAL_STATES);

        List<Task<? extends Serializable>> tasks = repository.getTasks(filter).toList();

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).id).isEqualTo(bar.id);
    }

    @Test
    public void test_get_task_states() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("user", User.local()));
        repository.insert(foo, new Group(TaskGroupType.Test));
        repository.insert(bar, new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty();

        List<TaskStateMetadata> tasks = repository.getTaskStates(filter).toList();

        assertThat(tasks.size()).isEqualTo(2);
        assertThat(tasks.stream().map(TaskStateMetadata::taskId).toList()).isEqualTo(List.of(foo.id, bar.id));
    }


    @Test
    public void test_get_task_states_with_names_filter() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("user", User.local()));
        repository.insert(foo, new Group(TaskGroupType.Test));
        repository.insert(bar, new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty().withNames("foo");

        List<TaskStateMetadata> tasks = repository.getTaskStates(filter).toList();

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).taskId()).isEqualTo(foo.id);
    }

    @Test
    public void test_get_task_states_with_status_filter() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("user", User.local()));
        // Bar must have a result to be considered as "DONE"
        bar.setResult(new TaskResult<>("1"));
        repository.insert(foo, new Group(TaskGroupType.Test));
        repository.insert(bar, new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty().withStates(Task.State.FINAL_STATES);

        List<TaskStateMetadata> tasks = repository.getTaskStates(filter).toList();

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).taskId()).isEqualTo(bar.id);
    }

    @Test
    public void test_get_task_states_with_args_filter() throws Exception {
        Task<String> foo = new Task<>("foo", User.local(), Map.of("someArg", "fooValue"));
        Task<String> bar = new Task<>("bar", User.local(), Map.of("someArg", "barValue"));

        repository.insert(foo, new Group(TaskGroupType.Test));
        repository.insert(bar, new Group(TaskGroupType.Test));

        TaskFilters filter = TaskFilters.empty()
            .withArgs(new TaskFilters.ArgsFilter("someArg", "bar.*"));

        List<TaskStateMetadata> tasks = repository.getTaskStates(filter).toList();

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).taskId()).isEqualTo(bar.id);
    }

    @After
    public void tearDown() throws Exception {
        repository.deleteAll();
    }

    @AfterClass
    public static void shutdownPools() {
        for (DbSetupRule rule : rulesToClose) {
            rule.shutdown();
        }
    }

    public JooqTaskRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createTaskRepository();
        rulesToClose.add(dbRule);
    }
}