package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

public class PathDeserializerTest {
    @Test
    public void test_deserialize() throws IOException {
        assertThat(JsonObjectMapper.readValue("{\"projectId\":\"prj\",\"id\":\"id\",\"path\":\"/tmp/file.txt\",\"content\":\"id\",\"language\":\"ENGLISH\",\"extractionDate\":\"2021-09-01T14:50:25.096Z\",\"contentEncoding\":\"UTF-8\",\"contentType\":\"text/plain\",\"extractionLevel\":0,\"metadata\":{},\"status\":\"INDEXED\",\"nerTags\":[],\"parentDocument\":null,\"rootDocument\":\"id\",\"contentLength\":2,\"tags\":[],\"dirname\":\"/tmp\",\"contentTextLength\":2,\"creationDate\":null}",
                Document.class)).isNotNull();
    }

    @Test
    public void test_deserialize_with_types() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectMapper.DefaultTypeResolverBuilder typer = new ObjectMapper.DefaultTypeResolverBuilder(ObjectMapper.DefaultTyping.NON_FINAL, BasicPolymorphicTypeValidator.builder().
                allowIfBaseType(Document.class).
                allowIfBaseType(Project.class).
                allowIfBaseType(Date.class).
                allowIfBaseType(Charset.class).
                allowIfBaseType(Set.class).
                allowIfBaseType(Map.class).
                allowIfBaseType(java.nio.file.Path.class).build());
        typer.init(JsonTypeInfo.Id.CLASS, null);
        typer.inclusion(JsonTypeInfo.As.PROPERTY);
        objectMapper.setDefaultTyping(typer);

        assertThat(objectMapper.readValue("{\"@class\":\"org.icij.datashare.text.Document\",\"projectId\":{\"@class\":\"org.icij.datashare.text.Project\", \"name\":\"prj\",\"sourcePath\":null},\"id\":\"id\",\"path\":[\"java.nio.file.Path\",\"file:///tmp/file.txt\"],\"content\":\"id\",\"language\":\"ENGLISH\",\"extractionDate\":[\"java.util.Date\",\"2021-09-01T15:05:47.894Z\"],\"contentEncoding\":{\"@class\":\"sun.nio.cs.UTF_8\"},\"contentType\":\"text/plain\",\"extractionLevel\":0,\"metadata\":{\"@class\":\"java.util.HashMap\"},\"status\":\"INDEXED\",\"nerTags\":[\"java.util.HashSet\",[]],\"parentDocument\":null,\"rootDocument\":\"id\",\"contentLength\":2,\"tags\":[\"java.util.HashSet\",[]],\"dirname\":[\"java.nio.file.Path\",\"/tmp\"],\"contentTextLength\":2,\"creationDate\":null}",
                Document.class)).isNotNull();
    }
}