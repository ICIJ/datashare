package org.icij.datashare.asynctasks;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;

import static org.fest.assertions.Assertions.assertThat;

public class TaskResultTest {
    private ObjectMapper typeInclusionMapper = JsonObjectMapper.createTypeInclusionMapper();

    @Test
    public void test_serialize_deserialize_uri() throws Exception {
        TaskResult<UriResult> value = new TaskResult<>(new UriResult(new URI("file:///my/file"), 12));
        String json = typeInclusionMapper.writeValueAsString(value);

        assertThat(typeInclusionMapper.readValue(json, TaskResult.class)).isEqualTo(value);
    }

    @Test
    public void test_serialize_deserialize_long() throws Exception {
        TaskResult<Long> value = new TaskResult<>(3L);
        String json = typeInclusionMapper.writeValueAsString(value);

        assertThat(typeInclusionMapper.readValue(json, TaskResult.class)).isEqualTo(value);
    }

    @Before
    public void setUp() {
        typeInclusionMapper.registerSubtypes(UriResult.class, Long.class);
    }
}