package org.icij.datashare.tasks;


import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import org.junit.Test;

public class DatashareTaskResultTest {
    @Test
    public void test_serialize_deserialize() throws Exception {
        DatashareTaskResult<UriResult> value = new DatashareTaskResult<>(new UriResult(new URI("file:///my/file"), 12));
        String json = MAPPER.writeValueAsString(value);

        DatashareTaskResult<UriResult> actual = MAPPER.readValue(json, new TypeReference<>() {});
        assertThat(actual).isEqualTo(value);
    }
}
