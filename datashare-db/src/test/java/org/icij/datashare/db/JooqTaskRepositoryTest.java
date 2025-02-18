package org.icij.datashare.db;

import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
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
        assertThat(repository.put("my_key", new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_id_null() {
        assertThat(repository.put("my_key", new Task<>(null, "foo", User.local(),new Group(TaskGroupType.Test), Map.of())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_null_key() {
        assertThat(repository.put(null, new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of())));
    }

    @Test
    public void test_put_get() {
        Task<?> foo = new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of("user", User.local()));

        assertThat(repository.put(foo.getId(), foo)).isNull();

        Task<?> actual = repository.get(foo.getId());
        assertThat(actual).isNotSameAs(foo); // not same instance
        assertThat(actual).isEqualTo(foo); // but equals as defined by Task
        assertThat(actual.getUser()).isEqualTo(User.local());
    }

    @Test
    public void test_upsert_already_exists() {
        Task<Integer> foo = new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of("user", User.local()));

        repository.save(foo);
        foo.setProgress(0.5);
        assertThat(repository.put(foo.getId(), foo).getState()).isEqualTo(Task.State.CREATED);

        Task<?> actual = repository.get(foo.getId());
        assertThat(actual.getState()).isEqualTo(Task.State.RUNNING);
        assertThat(actual.getCompletedAt()).isNull();


        foo.setResult(new TaskResult<>(1));
        repository.put(foo.getId(), foo);
        actual = repository.get(foo.getId());
        assertThat(actual.getState()).isEqualTo(Task.State.DONE);
        assertThat(actual.getCompletedAt()).isNotNull();
    }

    @Test
    public void test_size() {
        assertThat(repository.size()).isEqualTo(0);
        repository.save(new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of()));
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    public void test_empty() {
        assertThat(repository.isEmpty()).isTrue();
        repository.save(new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of()));
        assertThat(repository.isEmpty()).isFalse();
    }

    @Test
    public void test_contains_key() {
        Task<?> foo = new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of());
        assertThat(repository.containsKey(foo.getId())).isFalse();
        repository.save(foo);
        assertThat(repository.containsKey(foo.getId())).isTrue();
    }

    @Test
    public void test_contains_value() {
        Task<?> foo = new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of());
        assertThat(repository.containsValue(foo)).isFalse();
        repository.save(foo);
        assertThat(repository.containsValue(foo)).isTrue();
    }

    @Test
    public void test_remove() {
        Task<?> foo = new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of());
        assertThat(repository.remove(foo.getId())).isNull();
        repository.save(foo);
        assertThat(repository.remove(foo.getId())).isEqualTo(foo);
        assertThat(repository.isEmpty()).isTrue();
    }

    @Test
    public void test_get_result() throws URISyntaxException {
        Task<UriResult> foo = new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of("user", User.local()));
        repository.save(foo);

        TaskResult<UriResult> result = new TaskResult<>(new UriResult(new URI("file:///my/file"), 123));
        foo.setResult(result);
        repository.save(foo);
        assertThat(repository.get(foo.getId()).getError()).isNull();
        assertThat(repository.get(foo.getId()).getResult()).isEqualTo(result);
    }

    @Test
    public void test_get_error() {
        Task<String> foo = new Task<>("foo", User.local(), new Group(TaskGroupType.Test), Map.of("user", User.local()));
        repository.save(foo);

        foo.setError(new TaskError(new RuntimeException("boom")));
        repository.save(foo);
        assertThat(repository.get(foo.getId()).getResult()).isNull();
        assertThat(repository.get(foo.getId()).getError().getMessage()).isEqualTo("boom");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_putAll_null() {
        repository.putAll(null);
    }

    @Test
    public void test_putAll() {
        Map<String, Task<?>> map = Map.of("id_foo", new Task<>("id_foo", "foo", User.local(), new Group(TaskGroupType.Test), Map.of()), "id_bar", new Task<>("id_bar", "bar", User.local(), new Group(TaskGroupType.Test), Map.of()));
        assertThat(repository.isEmpty()).isTrue();

        repository.putAll(map);

        assertThat(repository.size()).isEqualTo(2);
        assertThat(repository.containsKey("id_foo")).isTrue();
        assertThat(repository.containsKey("id_bar")).isTrue();
    }

    @Test
    public void test_keySet() {
        repository.putAll(Map.of("id_foo", new Task<>("id_foo", "foo", User.local(), new Group(TaskGroupType.Test), Map.of()), "id_bar", new Task<>("id_bar", "bar", User.local(), new Group(TaskGroupType.Test), Map.of())));
        assertThat(repository.keySet()).containsOnly("id_foo", "id_bar");
    }

    @Test
    public void test_values() {
        Task<?> taskFoo = new Task<>("id_foo", "foo", User.local(), new Group(TaskGroupType.Test), Map.of());
        Task<?> taskBar = new Task<>("id_bar", "bar", User.local(), new Group(TaskGroupType.Test), Map.of());
        repository.putAll(Map.of(taskFoo.getId(), taskFoo, taskBar.getId(), taskBar));
        assertThat(repository.values()).containsOnly(taskFoo, taskBar);
    }

    @Test
    public void test_entrySet() {
        Task<?> taskFoo = new Task<>("id_foo", "foo", User.local(), new Group(TaskGroupType.Test), Map.of());
        Task<?> taskBar = new Task<>("id_bar", "bar", User.local(), new Group(TaskGroupType.Test), Map.of());
        repository.putAll(Map.of(taskFoo.getId(), taskFoo, taskBar.getId(), taskBar));
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