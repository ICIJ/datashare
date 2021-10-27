package org.icij.datashare.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class SearchExceptionTest {
    @Test
    public void test_serialize_deserialize() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.writerFor(new TypeReference<List<Throwable>>() { });

        Throwable throwable = new Throwable();
        SearchException se = new SearchException("unknown", throwable);
        String json = objectMapper.writeValueAsString(se);

        assertThat(json).contains(throwable.toString());
        assertThat(objectMapper.readValue(json, SearchException.class)).isNotNull();
    }
}