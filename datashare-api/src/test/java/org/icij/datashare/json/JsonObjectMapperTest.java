package org.icij.datashare.json;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.json.JsonObjectMapper.*;

public class JsonObjectMapperTest {
    record WithDuration(java.time.Duration timeout) {}

    @Test
    public void test_duration_from_cli_human_form_iso_and_own_serialization() throws Exception {
        assertThat(getMapper().readValue("{\"timeout\":\"30m\"}", WithDuration.class).timeout()).isEqualTo(java.time.Duration.ofMinutes(30));
        assertThat(getMapper().readValue("{\"timeout\":\"24h\"}", WithDuration.class).timeout()).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(getMapper().readValue("{\"timeout\":\"0\"}", WithDuration.class).timeout()).isEqualTo(java.time.Duration.ZERO);
        assertThat(getMapper().readValue("{\"timeout\":\"PT5S\"}", WithDuration.class).timeout()).isEqualTo(java.time.Duration.ofSeconds(5));
        WithDuration original = new WithDuration(java.time.Duration.ofMillis(1500));
        assertThat(getMapper().readValue(getMapper().writeValueAsString(original), WithDuration.class)).isEqualTo(original);
    }

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


    @Test(expected = InvalidTypeIdException.class)
    public void test_typed_mapper_rejects_dangerous_class() throws Exception {
        String malicious = "{\"@type\":\"javax.naming.InitialContext\",\"environment\":{}}";
        JsonObjectMapper.readValueTyped(malicious.getBytes(), Object.class);
    }

    @Test(expected = InvalidTypeIdException.class)
    public void test_typed_mapper_rejects_arbitrary_class() throws Exception {
        String malicious = "{\"@type\":\"java.io.File\",\"path\":\"/etc/passwd\"}";
        JsonObjectMapper.readValueTyped(malicious.getBytes(), Object.class);
    }

    static class ExceptionWrapper {
        private final Throwable throwable;

        @JsonCreator
        ExceptionWrapper(@JsonProperty("throwable") Throwable throwable) {
            this.throwable = throwable;
        }
    }
}