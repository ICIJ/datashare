package org.icij.datashare;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Project;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;


public class NoteTest {
    @Test
    public void serialize_note() throws JsonProcessingException {
        Note note = new Note(Project.project("foo"), Paths.get("toto/tata"),"This is a test");
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(note))
                .contains("\"name\":\"foo\"")
                .contains("\"label\":\"foo\"")
                .contains("\"sourcePath\":\"file:///vault/foo\"")
                .contains("\"note\":\"This is a test\"")
                .contains("\"blurSensitiveMedia\":false")
                .contains("\"variant\":\"info\"");
    }

    @Test
    public void deserialize_note() throws IOException {
        Note note = JsonObjectMapper.MAPPER.readValue("{\"project\":{\"name\":\"project\",\"sourcePath\":\"file:///vault/foo\",\"id\":\"foo\"},\"note\":\"This is a test\",\"path\":\"toto/tata\",\"variant\":\"info\", \"blurSensitiveMedia\":false}", Note.class);
        assertThat(note.path.toString()).isEqualTo("toto/tata");
        assertThat(note.project.getId()).isEqualTo("project");
        assertThat(note.note).isEqualTo("This is a test");
        assertThat(note.blurSensitiveMedia).isEqualTo(false);
        assertThat(note.variant).isEqualTo(Note.Variant.info);
    }
}
