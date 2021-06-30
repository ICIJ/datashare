package org.icij.datashare.text;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class PathSerializeDeserializeTest {
    @Test
    public void test_serialize() {
        Document document = DocumentBuilder.createDoc("id").with(Paths.get("/tmp/file.txt")).build();
        assertThat(JsonObjectMapper.getJson(document)).includes(entry("path", "/tmp/file.txt"));
    }
}