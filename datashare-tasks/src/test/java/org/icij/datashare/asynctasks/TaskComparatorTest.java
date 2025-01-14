package org.icij.datashare.asynctasks;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.ASC;
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.DESC;

public class TaskComparatorTest {
    @Test
    public void test_default_comparator() {
        assertThat(Stream.of(
                new Task<>("t2", new LinkedHashMap<>()),
                new Task<>("t1", new LinkedHashMap<>())
        ).sorted().map(t -> t.name).collect(Collectors.toList())).isEqualTo(List.of("t1", "t2"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_unknown_field() {
        new Task.Comparator("unknown");
    }

    @Test
    public void test_comparator_with_field() {
        assertThat(Stream.of(
                new Task<>("t1", new LinkedHashMap<>()),
                new Task<>("t2", new LinkedHashMap<>())
        ).sorted(new Task.Comparator("name", ASC)).map(t -> t.name).collect(Collectors.toList())).isEqualTo(List.of("t1", "t2"));
    }

    @Test
    public void test_comparator_with_desc_sort_direction() {
        assertThat(Stream.of(
                new Task<>("t1", new LinkedHashMap<>()),
                new Task<>("t2", new LinkedHashMap<>())
        ).sorted(new Task.Comparator("name", DESC)).map(t -> t.name).collect(Collectors.toList())).isEqualTo(List.of("t2", "t1"));
    }
}
