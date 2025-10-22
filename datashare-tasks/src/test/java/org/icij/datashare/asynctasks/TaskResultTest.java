package org.icij.datashare.asynctasks;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;

import static org.fest.assertions.Assertions.assertThat;

public class TaskResultTest {
    @Test
    public void test_serialize_deserialize_uri() throws Exception {
        TaskResult<UriResult> value = new TaskResult<>(new UriResult(new URI("file:///my/file"), 12));
        String json = JsonObjectMapper.writeValueAsString(value);

        assertThat(JsonObjectMapper.readValue(json, TaskResult.class)).isEqualTo(value);
    }

    @Test
    public void test_serialize_deserialize_long() throws Exception {
        TaskResult<Long> value = new TaskResult<>(3L);
        String json = JsonObjectMapper.writeValueAsString(value);

        assertThat(JsonObjectMapper.readValue(json, TaskResult.class)).isEqualTo(value);
    }

    @Before
    public void setUp() {
        JsonObjectMapper.registerSubtypes(new NamedType(UriResult.class), new NamedType(Long.class));
    }
}