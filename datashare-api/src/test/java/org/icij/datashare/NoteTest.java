package org.icij.datashare;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Project;
import org.junit.Test;

import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;


public class NoteTest {
    @Test
    public void serialize_note() throws JsonProcessingException {
        assertThat(JsonObjectMapper.MAPPER.writeValueAsString(new Note(Project.project("projet"), Paths.get("toto/tata"),"This is a test"))).isEqualTo("{\"project\":{\"name\":\"projet\",\"sourcePath\":\"file:///vault/projet\"},\"path\":\"toto/tata\",\"note\":\"This is a test\",\"variant\":\"info\"}");
    }

    @Test
    public void deserialize_note() throws JsonProcessingException {
        Note note = JsonObjectMapper.MAPPER.readValue("{\"project\":{\"name\":\"project\",\"sourcePath\":\"file:///vault/projet\",\"id\":\"projet\"},\"note\":\"This is a test\",\"path\":\"toto/tata\",\"variant\":\"info\"}", Note.class);
        assertThat(note.path.toString()).isEqualTo("toto/tata");
        assertThat(note.project.getId()).isEqualTo("project");
        assertThat(note.note).isEqualTo("This is a test");
        assertThat(note.variant).isEqualTo(Note.Variant.info);
    }
}