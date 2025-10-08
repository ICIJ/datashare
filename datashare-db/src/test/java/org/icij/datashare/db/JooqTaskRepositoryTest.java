package org.icij.datashare.db;

import java.io.IOException;
import java.util.List;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.user.User;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.URI;
import java.util.Collection;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.junit.Assert.assertThrows;

@RunWith(Parameterized.class)
public class JooqTaskRepositoryTest {
    @Rule
    public DbSetupRule dbRule;
    private final JooqTaskRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }

    @Test
    public void test_insert_with_null_id_should_throw() {
        Task task = new Task(null, "foo", User.local(), Map.of());
        assertThrows(IntegrityConstraintViolationException.class, () -> repository.insert(task, new Group(TaskGroupType.Test)));
    }

    @Test
    public void test_insert() throws IOException {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));

        repository.insert(foo, new Group(TaskGroupType.Test));

        Task actual = repository.getTask(foo.getId());
        Group actualGroup = repository.getTaskGroup(foo.getId());
        assertThat(actual).isNotSameAs(foo); // not same instance
        assertThat(actual).isEqualTo(foo); // but equals as defined by Task
        assertThat(actual.getUser()).isEqualTo(User.local());
        assertThat(actualGroup.getId()).isEqualTo("Test");
    }

    @Test
    public void test_insert_should_throw_already_exists() throws IOException {
        // Given
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        // When
        repository.insert(foo, new Group(TaskGroupType.Test));
        // Then
        assertThrows(TaskAlreadyExists.class, () -> repository.insert(foo, new Group(TaskGroupType.Test)));
    }

    @Test
    public void test_update() throws Exception {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));

        repository.insert(foo, new Group(TaskGroupType.Test));
        assertThat(repository.getTask(foo.id).getState()).isEqualTo(Task.State.CREATED);
        foo.setProgress(0.5);
        repository.update(foo);

        Task actual = repository.getTask(foo.getId());
        Group actualGroup = repository.getTaskGroup(foo.getId());
        assertThat(actualGroup).isEqualTo(new Group(TaskGroupType.Test));
        assertThat(actual.getState()).isEqualTo(Task.State.RUNNING);
        assertThat(actual.getCompletedAt()).isNull();

        foo.setResult(MAPPER.writeValueAsBytes(1));
        repository.update(foo);
        actual = repository.getTask(foo.getId());
        assertThat(actual.getState()).isEqualTo(Task.State.DONE);
        assertThat(actual.getCompletedAt()).isNotNull();
    }

    @Test
    public void test_update_should_throw_unknown_task() {
        // Given
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        // When/Then
        assertThrows(UnknownTask.class, () -> repository.update(foo));
    }


    @Test
    public void test_delete() throws IOException {
        Task foo = new Task("foo", User.local(), Map.of());
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
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        repository.insert(foo,  new Group(TaskGroupType.Test));
        byte[] result = MAPPER.writeValueAsBytes(new URI("file:///my/file"));

        foo.setResult(result);
        repository.update(foo);

        Task task = repository.getTask(foo.getId());
        assertThat(task.getError()).isNull();
        assertThat(task.getResult()).isEqualTo(result);
    }

    @Test
    public void test_get_error() throws Exception {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        repository.insert(foo,  new Group(TaskGroupType.Test));

        foo.setError(new TaskError(new RuntimeException("boom")));
        repository.update(foo);

        Task task = repository.getTask(foo.getId());
        assertThat(task.getResult()).isNull();
        assertThat(task.getError().getMessage()).isEqualTo("boom");
    }

    @Test
    public void test_get_tasks() throws Exception {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        Task bar = new Task("bar", User.local(), Map.of("user", User.local()));
        repository.insert(foo,  new Group(TaskGroupType.Test));
        repository.insert(bar,  new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty();

        List<Task> tasks = repository.getTasks(filter).toList();

        assertThat(tasks.size()).isEqualTo(2);
        assertThat(tasks.stream().map(Task::getId).toList()).isEqualTo(List.of(foo.id, bar.id));
    }

    @Test
    public void test_get_tasks_with_filter() throws Exception {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        Task bar = new Task("bar", User.local(), Map.of("user", User.local()));
        repository.insert(foo,  new Group(TaskGroupType.Test));
        repository.insert(bar,  new Group(TaskGroupType.Test));
        TaskFilters filter = TaskFilters.empty().withNames("foo");

        List<Task> tasks = repository.getTasks(filter).toList();

        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).id).isEqualTo(foo.id);
    }

    @After
    public void tearDown() throws Exception {
        repository.deleteAll();
    }

    public JooqTaskRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createTaskRepository();
    }
}