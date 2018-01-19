package org.icij.datashare.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class OptionsWrapperTest {
    ObjectMapper mapper = new ObjectMapper();

    @Test
    public void test_empty_options_serialization() throws Exception {
        assertThat(mapper.writeValueAsString(new OptionsWrapper())).contains("{\"options\":{}}");
    }

    @Test
    public void test_options_serialization() throws Exception {
        OptionsWrapper options = new OptionsWrapper(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }});
        assertThat(mapper.writeValueAsString(options)).contains("{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}");
    }
}