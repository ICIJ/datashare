package org.icij.datashare.json;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.*;

public class JsonObjectMapperTest {
    @Test
    public void test_type_inclusion_mapper() throws Exception {
        String json = JsonObjectMapper.writeValueAsStringTyped(new ExceptionWrapper(new RuntimeException("hello")));
        assertThat(json).contains("\"@type\":\"java.lang.RuntimeException\"");

        ExceptionWrapper wrapper = JsonObjectMapper.readValueTyped(json.getBytes(), ExceptionWrapper.class);
        assertThat(wrapper.throwable.getClass()).isEqualTo(RuntimeException.class);
        assertThat(wrapper.throwable.getMessage()).isEqualTo("hello");
    }

    @Test
    public void test_type_inclusion_mapper_for_suppressed_exceptions() throws Exception {
        RuntimeException hello = new RuntimeException("hello");
        hello.addSuppressed(new RuntimeException("world"));

        ExceptionWrapper wrapper = JsonObjectMapper.readValueTyped(
                JsonObjectMapper.writeValueAsStringTyped(new ExceptionWrapper(hello)).getBytes(), ExceptionWrapper.class);
        assertThat(wrapper.throwable.getClass()).isEqualTo(RuntimeException.class);
        assertThat(wrapper.throwable.getMessage()).isEqualTo("hello");
    }

    @Test
    public void test_check_stream_read_constraints() throws Exception {
        StreamReadConstraints streamReadConstraints = JsonObjectMapper.getFactory().streamReadConstraints();
        assertThat(streamReadConstraints.getMaxNestingDepth()).isEqualTo(MAX_NESTING_DEPTH);
        assertThat(streamReadConstraints.getMaxNumberLength()).isEqualTo(MAX_NUMBER_LENGTH);
        assertThat(streamReadConstraints.getMaxStringLength()).isEqualTo(MAX_STRING_LENGTH);
    }


    static class ExceptionWrapper {
        private final Throwable throwable;

        @JsonCreator
        ExceptionWrapper(@JsonProperty("throwable") Throwable throwable) {
            this.throwable = throwable;
        }
    }
}