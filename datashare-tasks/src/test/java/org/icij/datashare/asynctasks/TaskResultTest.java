package org.icij.datashare.asynctasks;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.net.URI;

import static org.fest.assertions.Assertions.assertThat;

public class TaskResultTest {
    @Test
    public void test_serialize_deserialize() throws Exception {
        ObjectMapper typeInclusionMapper = JsonObjectMapper.createTypeInclusionMapper();
        typeInclusionMapper.registerSubtypes(UriResult.class);

        TaskResult<UriResult> value = new TaskResult<>(new UriResult(new URI("file:///my/file"), 12));
        String json = typeInclusionMapper.writeValueAsString(value);

        assertThat(json).isEqualTo("{\"@type\":\"UriResult\",\"uri\":\"file:///my/file\",\"size\":12}");

        TaskResult actual = typeInclusionMapper.readValue(json, TaskResult.class);
        assertThat(actual).isEqualTo(value);
    }
}