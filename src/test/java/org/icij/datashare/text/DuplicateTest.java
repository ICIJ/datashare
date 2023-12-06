package org.icij.datashare.text;

import junit.framework.TestCase;
import org.icij.datashare.json.JsonObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Objects.requireNonNull;
import static org.fest.assertions.Assertions.assertThat;

public class DuplicateTest extends TestCase {
    public void test_serialize() throws Exception {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(
                new Duplicate(Paths.get(requireNonNull(getClass().getResource("/sampleFile.txt")).getPath()), "docId"))).
                contains("\"id\":\"768412320f7b0aa5812fce428dc4706b3cae50e02a64caa16a782249bfe8efc4b7ef1ccb126255d196047dfedf17a0a9\"");
    }

    public void test_deserialize() throws Exception {
        Path path = Paths.get(requireNonNull(getClass().getResource("/sampleFile.txt")).getPath());

        Duplicate duplicate = JsonObjectMapper.MAPPER.readValue(
                (String.format("{\"id\":\"768412320f7b0aa5812fce428dc4706b3cae50e02a64caa16a782249bfe8efc4b7ef1ccb126255d196047dfedf17a0a9\"," +
                "\"path\":\"%s\",\"documentId\":\"docId\"}", path)).getBytes(), Duplicate.class);

        assertThat(duplicate.path.toString()).isEqualTo(path.toString());
        assertThat(duplicate.documentId).isEqualTo("docId");
    }
}