package org.icij.datashare.text;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.fest.assertions.Assertions.assertThat;

public class CharsetDeserializerTest {
    private ObjectMapper mapper = new ObjectMapper();
    private CharsetDeserializer deserializer = new CharsetDeserializer();

    @Before
    public void setUp() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Charset.class, deserializer);
        mapper.registerModule(module);
    }

    @Test
    public void test_deserialize_unknown_charset() throws Exception {
        JsonParser parser = mapper.getFactory().createParser(new ByteArrayInputStream("{\"charset\":\"unknown\"}".getBytes()));
        ValueObject object = mapper.readValue(parser, ValueObject.class);
        assertThat(object.charset).isEqualTo(StandardCharsets.US_ASCII);
    }

    @Test
    public void test_deserialize_utf8_charset() throws Exception {
        JsonParser parser = mapper.getFactory().createParser(new ByteArrayInputStream("{\"charset\":\"utf-8\"}".getBytes()));
        ValueObject object = mapper.readValue(parser, ValueObject.class);
        assertThat(object.charset).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    public void test_deserialize_capitalized_utf8_charset() throws Exception {
        JsonParser parser = mapper.getFactory().createParser(new ByteArrayInputStream("{\"charset\":\"UTF-8\"}".getBytes()));
        ValueObject object = mapper.readValue(parser, ValueObject.class);
        assertThat(object.charset).isEqualTo(StandardCharsets.UTF_8);
    }

    static class ValueObject {
        @JsonDeserialize(using = CharsetDeserializer.class)
        Charset charset;
    }
}