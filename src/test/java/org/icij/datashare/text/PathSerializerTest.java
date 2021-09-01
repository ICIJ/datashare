package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class PathSerializerTest {
    @Test
    public void test_serialize() {
        Document document = DocumentBuilder.createDoc("id").with(Paths.get("/tmp/file.txt")).build();
        assertThat(JsonObjectMapper.getJson(document)).includes(entry("path", "/tmp/file.txt"));
    }

    @Test
    public void test_serialize_with_type() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        Document document = DocumentBuilder.createDoc("id").with(Paths.get("/tmp/file.txt")).build();
        ObjectMapper.DefaultTypeResolverBuilder typer = new ObjectMapper.DefaultTypeResolverBuilder(ObjectMapper.DefaultTyping.NON_FINAL, BasicPolymorphicTypeValidator.builder().build());
        typer.init(JsonTypeInfo.Id.CLASS, null);
        typer.inclusion(JsonTypeInfo.As.PROPERTY);
        objectMapper.setDefaultTyping(typer);

        String json = objectMapper.writeValueAsString(document);

        assertThat(json).contains("\"path\":[\"sun.nio.fs.UnixPath\",\"/tmp/file.txt\"]");
    }


}