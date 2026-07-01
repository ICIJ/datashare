package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;

public class ManifestEntryStatusTest {
    private final ObjectMapper mapper = JsonObjectMapper.getMapper();

    @Test
    public void test_serializes_to_lowercase_token() throws Exception {
        assertThat(mapper.writeValueAsString(ManifestEntryStatus.COMPLETE)).isEqualTo("\"complete\"");
        assertThat(mapper.writeValueAsString(ManifestEntryStatus.EMPTY)).isEqualTo("\"empty\"");
    }

    @Test
    public void test_deserializes_from_token() throws Exception {
        assertThat(mapper.readValue("\"empty\"", ManifestEntryStatus.class)).isEqualTo(ManifestEntryStatus.EMPTY);
    }

    @Test
    public void test_terminal_and_servable_semantics() {
        assertThat(ManifestEntryStatus.COMPLETE.isTerminal()).isTrue();
        assertThat(ManifestEntryStatus.COMPLETE.isServable()).isTrue();
        assertThat(ManifestEntryStatus.EMPTY.isTerminal()).isTrue();
        assertThat(ManifestEntryStatus.EMPTY.isServable()).isFalse();
    }
}
