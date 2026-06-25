package org.icij.datashare.asynctasks;

import org.icij.datashare.tasks.TaskType;
import org.junit.Test;

import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;

public class TaskTypeTest {

    @Test
    public void test_fromName_known_fqdn_returns_type() {
        assertThat(TaskType.fromName("org.icij.datashare.tasks.BatchSearchRunner"))
            .isEqualTo(Optional.of(TaskType.BATCH_SEARCH));
    }

    @Test
    public void test_fromName_proxy_maps_to_batch_search() {
        assertThat(TaskType.fromName("org.icij.datashare.tasks.BatchSearchRunnerProxy"))
            .isEqualTo(Optional.of(TaskType.BATCH_SEARCH));
    }

    @Test
    public void test_fromName_unknown_fqdn_returns_empty() {
        assertThat(TaskType.fromName("org.unknown.SomeTask")).isEqualTo(Optional.empty());
    }

    @Test
    public void test_fromName_null_returns_empty() {
        assertThat(TaskType.fromName(null)).isEqualTo(Optional.empty());
    }

    @Test
    public void test_each_type_has_at_least_one_fqdn() {
        for (TaskType type : TaskType.values()) {
            assertThat(type.getNames()).isNotEmpty();
        }
    }
}
