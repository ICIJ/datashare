package org.icij.datashare.json;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class JsonObjectMapperTest {
    @Test
    public void test_type_inclusion_mapper() throws Exception {
        ObjectMapper typeInclusionMapper = JsonObjectMapper.createTypeInclusionMapper();

        String json = typeInclusionMapper.writeValueAsString(new ExceptionWrapper(new RuntimeException("hello")));
        assertThat(json).contains("\"@type\":\"java.lang.RuntimeException\"");

        ExceptionWrapper wrapper = typeInclusionMapper.readValue(json, ExceptionWrapper.class);
        assertThat(wrapper.throwable.getClass()).isEqualTo(RuntimeException.class);
        assertThat(wrapper.throwable.getMessage()).isEqualTo("hello");
    }

    @Test
    public void test_type_inclusion_mapper_for_suppressed_exceptions() throws Exception {
        ObjectMapper typeInclusionMapper = JsonObjectMapper.createTypeInclusionMapper();

        RuntimeException hello = new RuntimeException("hello");
        hello.addSuppressed(new RuntimeException("world"));

        ExceptionWrapper wrapper = typeInclusionMapper.readValue(
                typeInclusionMapper.writeValueAsString(new ExceptionWrapper(hello)), ExceptionWrapper.class);
        assertThat(wrapper.throwable.getClass()).isEqualTo(RuntimeException.class);
        assertThat(wrapper.throwable.getMessage()).isEqualTo("hello");
    }

    static class ExceptionWrapper {
        private final Throwable throwable;

        @JsonCreator
        ExceptionWrapper(@JsonProperty("throwable") Throwable throwable) {
            this.throwable = throwable;
        }
    }
}