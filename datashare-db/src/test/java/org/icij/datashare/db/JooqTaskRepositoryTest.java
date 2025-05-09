package org.icij.datashare.db;

import java.io.IOException;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.asynctasks.TaskMetadata;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.URI;
import java.net.URISyntaxException;
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

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_key_different_than_id() {
        assertThat(repository.put("my_key", new TaskMetadata(new Task("foo", User.local(), Map.of()), new Group(TaskGroupType.Test))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_id_null() {
        assertThat(repository.put("my_key", new TaskMetadata(new Task(null,"foo", User.local(), Map.of()), new Group(TaskGroupType.Test))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_null_key() {
        assertThat(repository.put(null, new TaskMetadata(new Task("foo", User.local(), Map.of()), new Group(TaskGroupType.Test))));
    }

    @Test
    public void test_put_get() {
        TaskMetadata foo = new TaskMetadata(new Task("foo", User.local(), Map.of("user", User.local())), new Group(TaskGroupType.Test));


        assertThat(repository.put(foo.taskId(), foo)).isNull();

        TaskMetadata actual = repository.get(foo.taskId());
        assertThat(actual).isNotSameAs(foo); // not same instance
        assertThat(actual).isEqualTo(foo); // but equals as defined by Task
        assertThat(actual.task().getUser()).isEqualTo(User.local());
        assertThat(actual.group().getId()).isEqualTo("Test");
    }

    @Test
    public void test_upsert_already_exists() throws TaskAlreadyExists, IOException, UnknownTask {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));

        repository.insert(foo, new Group(TaskGroupType.Test));
        assertThat(repository.getTask(foo.id).getState()).isEqualTo(Task.State.CREATED);
        foo.setProgress(0.5);
        repository.update(foo);

        TaskMetadata actual = repository.get(foo.getId());
        assertThat(actual.group()).isEqualTo(new Group(TaskGroupType.Test));
        assertThat(actual.task().getState()).isEqualTo(Task.State.RUNNING);
        assertThat(actual.task().getCompletedAt()).isNull();

        foo.setDone();
        repository.saveResult(foo.id, MAPPER.writeValueAsBytes(1));
        repository.update(foo);
        actual = repository.get(foo.getId());
        assertThat(actual.task().getState()).isEqualTo(Task.State.DONE);
        assertThat(actual.task().getCompletedAt()).isNotNull();
    }

    @Test
    public void test_size() throws TaskAlreadyExists, IOException {
        assertThat(repository.size()).isEqualTo(0);
        repository.insert(new Task("foo", User.local(), Map.of()), new Group(TaskGroupType.Test));
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    public void test_empty() throws TaskAlreadyExists, IOException {
        assertThat(repository.isEmpty()).isTrue();
        repository.insert(new Task("foo", User.local(), Map.of()), new Group(TaskGroupType.Test));
        assertThat(repository.isEmpty()).isFalse();
    }

    @Test
    public void test_contains_key() throws TaskAlreadyExists, IOException {
        Task foo = new Task("foo", User.local(), Map.of());
        assertThat(repository.containsKey(foo.getId())).isFalse();
        repository.insert(foo, new Group(TaskGroupType.Test));
        assertThat(repository.containsKey(foo.getId())).isTrue();
    }

    @Test
    public void test_contains_value() throws TaskAlreadyExists, IOException {
        Task foo = new Task("foo", User.local(), Map.of());
        assertThat(repository.containsValue(foo)).isFalse();
        repository.insert(foo, new Group(TaskGroupType.Test));
        assertThat(repository.containsValue(foo)).isTrue();
    }

    @Test
    public void test_remove() throws TaskAlreadyExists, IOException {
        Task foo = new Task("foo", User.local(), Map.of());
        assertThat(repository.remove(foo.getId())).isNull();
        repository.insert(foo, new Group(TaskGroupType.Test));
        assertThat(repository.remove(foo.getId())).isEqualTo(new TaskMetadata(foo, new Group(TaskGroupType.Test)));
        assertThat(repository.isEmpty()).isTrue();
    }

    @Test
    public void test_get_save_and_get_result() throws URISyntaxException, TaskAlreadyExists, IOException, UnknownTask {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        repository.insert(foo,  new Group(TaskGroupType.Test));

        URI result = new URI("file:///my/file");
        repository.saveResult(foo.id, MAPPER.writeValueAsBytes(result));
        repository.update(foo);
        assertThat(repository.getTask(foo.getId()).getError()).isNull();
        assertThat(MAPPER.readValue(repository.getResult(foo.getId()), URI.class)).isEqualTo(result);
    }

    @Test
    public void test_get_result_should_raise_for_unknown_task() {
        assertThrows(UnknownTask.class, () -> repository.getTask("i_dont_exist"));
    }

    @Test
    public void test_save_result_should_raise_for_unknown_task() {
        assertThrows(UnknownTask.class, () -> repository.saveResult("another_not_existing_task", new byte[] {}));
    }

    @Test
    public void test_save_result_should_raise_for_existing_result() throws IOException {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        repository.insert(foo,  new Group(TaskGroupType.Test));

        String result = "result";
        repository.saveResult(foo.id, MAPPER.writeValueAsBytes(result));
        assertThrows(RuntimeException.class, () -> repository.saveResult(foo.id, MAPPER.writeValueAsBytes(result)));
    }

    @Test
    public void test_get_error() throws TaskAlreadyExists, IOException, UnknownTask {
        Task foo = new Task("foo", User.local(), Map.of("user", User.local()));
        repository.insert(foo,  new Group(TaskGroupType.Test));

        foo.setError(new TaskError(new RuntimeException("boom")));
        repository.update(foo);
        assertThrows(UnknownTask.class, () -> repository.getResult(foo.getId()));
        assertThat(repository.getTask(foo.getId()).getError().getMessage()).isEqualTo("boom");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_putAll_null() {
        repository.putAll(null);
    }

    @Test
    public void test_putAll() {
        Map<String, TaskMetadata> map = Map.of(
            "id_foo", new TaskMetadata(new Task("id_foo", "foo", User.local(), Map.of()), new Group(TaskGroupType.Test)),
            "id_bar", new TaskMetadata(new Task("id_bar", "bar", User.local(), Map.of()), new Group(TaskGroupType.Test))
        );
        assertThat(repository.isEmpty()).isTrue();

        repository.putAll(map);

        assertThat(repository.size()).isEqualTo(2);
        assertThat(repository.containsKey("id_foo")).isTrue();
        assertThat(repository.containsKey("id_bar")).isTrue();
    }

    @Test
    public void test_keySet() {
        repository.putAll(Map.of("id_foo", new TaskMetadata(new Task("id_foo", "foo", User.local(), Map.of()), new Group(TaskGroupType.Test)),
            "id_bar", new TaskMetadata(new Task("id_bar", "bar", User.local(), Map.of()), new Group(TaskGroupType.Test))));
        assertThat(repository.keySet()).containsOnly("id_foo", "id_bar");
    }

    @Test
    public void test_values() {
        Task taskFoo = new Task("id_foo", "foo", User.local(), Map.of());
        Task taskBar = new Task("id_bar", "bar", User.local(), Map.of());
        TaskMetadata taskMetaFoo = new TaskMetadata(taskFoo, new Group(TaskGroupType.Test));
        TaskMetadata taskMetaBar = new TaskMetadata(taskBar, new Group(TaskGroupType.Test));
        repository.putAll(Map.of(taskFoo.getId(), taskMetaFoo, taskBar.getId(), taskMetaBar));
        assertThat(repository.values()).containsOnly(taskMetaFoo, taskMetaBar);
    }

    @Test
    public void test_entrySet() {
        Task taskFoo = new Task("id_foo", "foo", User.local(), Map.of());
        Task taskBar = new Task("id_bar", "bar", User.local(), Map.of());
        TaskMetadata taskMetaFoo = new TaskMetadata(taskFoo, new Group(TaskGroupType.Test));
        TaskMetadata taskMetaBar = new TaskMetadata(taskBar, new Group(TaskGroupType.Test));
        repository.putAll(Map.of(taskFoo.getId(), taskMetaFoo, taskBar.getId(), taskMetaBar));
        assertThat(repository.entrySet().stream().map(Map.Entry::getKey).toList()).containsOnly(taskFoo.getId(), taskBar.getId());
        assertThat(repository.entrySet().stream().map(Map.Entry::getValue).toList()).containsOnly(taskMetaFoo, taskMetaBar);
    }

    @After
    public void tearDown() {
        repository.clear();
    }

    public JooqTaskRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createTaskRepository();
    }
}