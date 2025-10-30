package org.icij.datashare.text;

import junit.framework.TestCase;
import org.icij.datashare.json.JsonObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Objects.requireNonNull;
import static org.fest.assertions.Assertions.assertThat;

public class DuplicateTest extends TestCase {
    public void test_serialize() throws Exception {
        Duplicate dup = new Duplicate(Paths.get(requireNonNull(getClass().getResource("/sampleFile.txt")).getPath()), "docId");
        assertThat(JsonObjectMapper.writeValueAsString(
                dup)).contains(String.format("\"id\":\"%s\"", dup.getId()));
    }

    public void test_deserialize() throws Exception {
        Path path = Paths.get(requireNonNull(getClass().getResource("/sampleFile.txt")).getPath());
        Duplicate dup = new Duplicate(Paths.get(requireNonNull(getClass().getResource("/sampleFile.txt")).getPath()), "docId");
        Duplicate duplicate = JsonObjectMapper.readValue(
                (String.format("{\"id\":\"%s\"," +
                "\"path\":\"%s\",\"documentId\":\"docId\",\"type\":\"Duplicate\"}", dup.getId(), path)), Duplicate.class);

        assertThat(duplicate.path.toString()).isEqualTo(path.toString());
        assertThat(duplicate.documentId).isEqualTo("docId");
    }
}