package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.ASC;
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.DESC;

public class DatashareTaskComparatorTest {
    @Test
    public void test_default_comparator() {
        assertThat(Stream.of(
                new Task("t2", User.local(), new LinkedHashMap<>()),
                new Task("t1", User.local(), new LinkedHashMap<>())
        ).sorted().map(t -> t.name).collect(Collectors.toList())).isEqualTo(List.of("t1", "t2"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_unknown_field() {
        new Task.Comparator("unknown");
    }

    @Test
    public void test_comparator_with_field() {
        assertThat(Stream.of(
                new Task("t1", User.localUser("b_user"), new LinkedHashMap<>()),
                new Task("t2", User.localUser("a_user"), new LinkedHashMap<>())
        ).sorted(new Task.Comparator("user", ASC)).map(t -> t.getUser().id).collect(Collectors.toList())).isEqualTo(List.of("a_user", "b_user"));
    }

    @Test
    public void test_comparator_with_desc_sort_direction() {
        assertThat(Stream.of(
                new Task("t1", User.local(), new LinkedHashMap<>()),
                new Task("t2", User.local(), new LinkedHashMap<>())
        ).sorted(new Task.Comparator("name", DESC)).map(t -> t.name).collect(Collectors.toList())).isEqualTo(List.of("t2", "t1"));
    }
}
