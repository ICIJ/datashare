package org.icij.datashare.db;

import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.asynctasks.TaskMetadata;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;

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
        assertThat(repository.put("my_key", new TaskMetadata<>(new Task<>("foo", User.local(), Map.of()), null)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_id_null() {
        assertThat(repository.put("my_key", new TaskMetadata<>(new Task<>(null,"foo", User.local(), Map.of()), null)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_null_key() {
        assertThat(repository.put(null, new TaskMetadata<>(new Task<>("foo", User.local(), Map.of()), null)));
    }

    @Test
    public void test_put_get() {
        TaskMetadata<Object> foo = new TaskMetadata<>(new Task<>("foo", User.local(), Map.of("user", User.local())), new Group("someGroup"));


        assertThat(repository.put(foo.taskId(), foo)).isNull();

        TaskMetadata<?> actual = repository.get(foo.taskId());
        assertThat(actual).isNotSameAs(foo); // not same instance
        assertThat(actual).isEqualTo(foo); // but equals as defined by Task
        assertThat(actual.task().getUser()).isEqualTo(User.local());
        assertThat(actual.group().getId()).isEqualTo("someGroup");
    }

    @Test
    public void test_upsert_already_exists() throws TaskAlreadyExists {
        Task<Integer> foo = new Task<>("foo", User.local(), Map.of("user", User.local()));

        repository.persist(foo, new Group("groupId"));
        foo.setProgress(0.5);
        assertThat(repository.update(foo).getState()).isEqualTo(Task.State.CREATED);

        TaskMetadata<?> actual = repository.get(foo.getId());
        assertThat(actual.group().id()).isEqualTo("groupId");
        assertThat(actual.task().getState()).isEqualTo(Task.State.RUNNING);
        assertThat(actual.task().getCompletedAt()).isNull();


        foo.setResult(1);
        repository.update(foo);
        actual = repository.get(foo.getId());
        assertThat(actual.task().getState()).isEqualTo(Task.State.DONE);
        assertThat(actual.task().getCompletedAt()).isNotNull();
    }

    @Test
    public void test_size() throws TaskAlreadyExists {
        assertThat(repository.size()).isEqualTo(0);
        repository.persist(new Task<>("foo", User.local(), Map.of()), null);
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    public void test_empty() throws TaskAlreadyExists {
        assertThat(repository.isEmpty()).isTrue();
        repository.persist(new Task<>("foo", User.local(), Map.of()), null);
        assertThat(repository.isEmpty()).isFalse();
    }

    @Test
    public void test_contains_key() throws TaskAlreadyExists {
        Task<Object> foo = new Task<>("foo", User.local(), Map.of());
        assertThat(repository.containsKey(foo.getId())).isFalse();
        repository.persist(foo, null);
        assertThat(repository.containsKey(foo.getId())).isTrue();
    }

    @Test
    public void test_contains_value() throws TaskAlreadyExists {
        Task<Object> foo = new Task<>("foo", User.local(), Map.of());
        assertThat(repository.containsValue(foo)).isFalse();
        repository.persist(foo, null);
        assertThat(repository.containsValue(foo)).isTrue();
    }

    @Test
    public void test_remove() throws TaskAlreadyExists {
        Task<Object> foo = new Task<>("foo", User.local(), Map.of());
        assertThat(repository.remove(foo.getId())).isNull();
        repository.persist(foo, null);
        assertThat(repository.remove(foo.getId())).isEqualTo(foo);
        assertThat(repository.isEmpty()).isTrue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_putAll_null() {
        repository.putAll(null);
    }

    @Test
    public void test_putAll() {
        Map<String, TaskMetadata<?>> map = Map.of(
            "id_foo", new TaskMetadata<>(new Task<>("id_foo", "foo", User.local(), Map.of()), null),
            "id_bar", new TaskMetadata<>(new Task<>("id_bar", "bar", User.local(), Map.of()), null)
        );
        assertThat(repository.isEmpty()).isTrue();

        repository.putAll(map);

        assertThat(repository.size()).isEqualTo(2);
        assertThat(repository.containsKey("id_foo")).isTrue();
        assertThat(repository.containsKey("id_bar")).isTrue();
    }

    @Test
    public void test_keySet() {
        repository.putAll(Map.of("id_foo", new TaskMetadata<>(new Task<>("id_foo", "foo", User.local(), Map.of()), null),
            "id_bar", new TaskMetadata<>(new Task<>("id_bar", "bar", User.local(), Map.of()), null)));
        assertThat(repository.keySet()).containsOnly("id_foo", "id_bar");
    }

    @Test
    public void test_values() {
        Task<Object> taskFoo = new Task<>("id_foo", "foo", User.local(), Map.of());
        Task<Object> taskBar = new Task<>("id_bar", "bar", User.local(), Map.of());
        repository.putAll(Map.of(taskFoo.getId(), new TaskMetadata<>(taskFoo, null), taskBar.getId(), new TaskMetadata<>(taskBar, null)));
        assertThat(repository.values()).containsOnly(taskFoo, taskBar);
    }

    @Test
    public void test_entrySet() {
        Task<Object> taskFoo = new Task<>("id_foo", "foo", User.local(), Map.of());
        Task<Object> taskBar = new Task<>("id_bar", "bar", User.local(), Map.of());
        repository.putAll(Map.of(taskFoo.getId(), new TaskMetadata<>(taskFoo, null), taskBar.getId(), new TaskMetadata<>(taskBar, null)));
        assertThat(repository.entrySet().stream().map(Map.Entry::getKey).toList()).containsOnly(taskFoo.getId(), taskBar.getId());
        assertThat(repository.entrySet().stream().map(Map.Entry::getValue).toList()).containsOnly(taskFoo, taskBar);
    }

    @After
    public void tearDown() throws Exception {
        repository.clear();
    }

    public JooqTaskRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createTaskRepository();
    }
}