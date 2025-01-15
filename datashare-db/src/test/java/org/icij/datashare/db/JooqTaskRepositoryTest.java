package org.icij.datashare.db;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.test.DatashareTimeRule;
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
    public DatashareTimeRule time = new DatashareTimeRule("2021-06-30T12:13:14Z");
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
        assertThat(repository.put("my_key", new Task<>("foo", User.local(), Map.of())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_id_null() {
        assertThat(repository.put("my_key", new Task<>(null, "foo", User.local(), Map.of())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_put_with_null_key() {
        assertThat(repository.put(null, new Task<>("foo", User.local(), Map.of())));
    }

    @Test
    public void test_put_get() {
        Task<Object> foo = new Task<>("foo", User.local(), Map.of());

        assertThat(repository.put(foo.getId(), foo)).isSameAs(foo);

        assertThat(repository.get(foo.getId())).isNotSameAs(foo); // not same instance
        assertThat(repository.get(foo.getId())).isEqualTo(foo); // but equals as defined by Task
    }

    @Test
    public void test_size() {
        assertThat(repository.size()).isEqualTo(0);
        repository.save(new Task<>("foo", User.local(), Map.of()));
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    public void test_empty() {
        assertThat(repository.isEmpty()).isTrue();
        repository.save(new Task<>("foo", User.local(), Map.of()));
        assertThat(repository.isEmpty()).isFalse();
    }

    @Test
    public void test_contains_key() {
        Task<Object> foo = new Task<>("foo", User.local(), Map.of());
        assertThat(repository.containsKey(foo.getId())).isFalse();
        repository.save(foo);
        assertThat(repository.containsKey(foo.getId())).isTrue();
    }

    @Test
    public void test_contains_value() {
        Task<Object> foo = new Task<>("foo", User.local(), Map.of());
        assertThat(repository.containsValue(foo)).isFalse();
        repository.save(foo);
        assertThat(repository.containsValue(foo)).isTrue();
    }

    @Test
    public void test_remove() {
        Task<Object> foo = new Task<>("foo", User.local(), Map.of());
        assertThat(repository.remove(foo.getId())).isNull();
        repository.save(foo);
        assertThat(repository.remove(foo.getId())).isEqualTo(foo);
        assertThat(repository.isEmpty()).isTrue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_putAll_null() {
        repository.putAll(null);
    }

    @Test
    public void test_putAll() {
        Map<String, Task<?>> map = Map.of("id_foo", new Task<>("id_foo", "foo", User.local(), Map.of()), "id_bar", new Task<>("id_bar", "bar", User.local(), Map.of()));
        assertThat(repository.isEmpty()).isTrue();

        repository.putAll(map);

        assertThat(repository.size()).isEqualTo(2);
        assertThat(repository.containsKey("id_foo")).isTrue();
        assertThat(repository.containsKey("id_bar")).isTrue();
    }

    @Test
    public void test_keySet() {
        repository.putAll(Map.of("id_foo", new Task<>("id_foo", "foo", User.local(), Map.of()), "id_bar", new Task<>("id_bar", "bar", User.local(), Map.of())));
        assertThat(repository.keySet()).containsOnly("id_foo", "id_bar");
    }

    @Test
    public void test_values() {
        Task<Object> taskFoo = new Task<>("id_foo", "foo", User.local(), Map.of());
        Task<Object> taskBar = new Task<>("id_bar", "bar", User.local(), Map.of());
        repository.putAll(Map.of(taskFoo.getId(), taskFoo, taskBar.getId(), taskBar));
        assertThat(repository.values()).containsOnly(taskFoo, taskBar);
    }

    @Test
    public void test_entrySet() {
        Task<Object> taskFoo = new Task<>("id_foo", "foo", User.local(), Map.of());
        Task<Object> taskBar = new Task<>("id_bar", "bar", User.local(), Map.of());
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