package org.icij.datashare.db;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.user.User;
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

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }



    public JooqTaskRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createTaskRepository();
    }
}