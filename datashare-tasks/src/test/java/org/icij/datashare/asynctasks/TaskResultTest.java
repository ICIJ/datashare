package org.icij.datashare.asynctasks;


import com.fasterxml.jackson.core.type.TypeReference;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.net.URI;

import static org.fest.assertions.Assertions.assertThat;

public class TaskResultTest {
    @Test
    public void test_serialize_deserialize() throws Exception {
        TaskResult<UriResult> value = new TaskResult<>(new UriResult(new URI("file:///my/file"), 12));
        String json = JsonObjectMapper.MAPPER.writeValueAsString(value);

        TaskResult<UriResult> actual = JsonObjectMapper.MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(actual).isEqualTo(value);
    }
}