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
                contains("\"id\":\"af3e968fd1070c183b301d1dd881fac45aca296455ccdda56686d91f53d00a0d8dd9d43fba1e1a1333b0f29e70189d5e\"");
    }

    public void test_deserialize() throws Exception {
        Path path = Paths.get(requireNonNull(getClass().getResource("/sampleFile.txt")).getPath());

        Duplicate duplicate = JsonObjectMapper.MAPPER.readValue(
                (String.format("{\"id\":\"af3e968fd1070c183b301d1dd881fac45aca296455ccdda56686d91f53d00a0d8dd9d43fba1e1a1333b0f29e70189d5e\"," +
                "\"path\":\"%s\",\"documentId\":\"docId\",\"type\":\"Duplicate\"}", path)).getBytes(), Duplicate.class);

        assertThat(duplicate.path.toString()).isEqualTo(path.toString());
        assertThat(duplicate.documentId).isEqualTo("docId");
    }
}