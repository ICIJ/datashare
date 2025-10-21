package org.icij.datashare.utils;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class JsonUtilsTest {

    @Test
    public void test_should_return_source_as_map() {
        ObjectNode node = JsonNodeFactory.instance.objectNode()
                .put("field1", "value1")
                .put("field2", "value2");
        assertThat(JsonUtils.nodeToMap(node)).includes(
              entry("field1", "value1"),
              entry("field2", "value2")
        );
    }
}