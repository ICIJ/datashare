package org.icij.datashare.text;

import org.icij.datashare.batch.BatchSearchRecord;
import org.junit.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.StringUtils.getValue;
import static org.icij.datashare.text.StringUtils.isEmpty;

public class StringUtilsTest  {

    @Test
    public void is_empty_test() {
        assertThat(isEmpty(null)).isTrue();
        assertThat(isEmpty("")).isTrue();
        assertThat(isEmpty("foo")).isFalse();
    }

    @Test
    public void get_nested_value_from_map() {
        Map<String, Object> map =  Map.of("foo", Map.of("bar", 2));
        assertThat(getValue(map, "foo.bar")).isEqualTo(2);
    }

    @Test
    public void get_null_for_unknown_nested_value_from_map() {
        Map<String, Object> map =  Map.of("foo", Map.of("bar", 2));
        assertThat(getValue(map, "foo.bus")).isEqualTo(null);
    }

    @Test
    public void get_nested_value_from_batch_search_record() {
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "foo", "bar", 123, new Date(), "/");
        Map<String, Object> batchRecord = Map.of("batchRecord", batchSearchRecord);
        assertThat(getValue(batchRecord, "batchRecord.name")).isEqualTo("foo");
    }

    @Test
    public void get_null_for_unknown_nested_value_from_batch_search_record() {
        List<ProjectProxy> projects = List.of(project("project"));
        BatchSearchRecord batchSearchRecord = new BatchSearchRecord(projects, "foo", "bar", 123, new Date(), "/");
        Map<String, Object> batchRecord = Map.of("batchRecord", batchSearchRecord);
        assertThat(getValue(batchRecord, "batchRecord.bus")).isEqualTo(null);
    }
}