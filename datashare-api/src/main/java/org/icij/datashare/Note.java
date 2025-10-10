package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.icij.datashare.text.PathDeserializer;
import org.icij.datashare.text.PathSerializer;
import org.icij.datashare.text.Project;

import java.nio.file.Path;
import java.util.Objects;

//@JsonSerialize(using = NoteSerializer.class)
public class Note {
    public enum Variant {
        dark, light, danger, info, success, warning, primary, secondary
    }
    public final Project project;
    public final String note;
    @JsonSerialize(using = PathSerializer.class)
    @JsonDeserialize(using = PathDeserializer.class)
    public final Path path;
    public final Variant variant;
    public final Boolean blurSensitiveMedia;

    public Note(Project project, Path path, String note) {
        this(project, path, note, Variant.info, false);
    }

    public Note(Project project, Path path, String note, Variant variant) {
        this(project, path, note, variant, false);
    }

    @JsonCreator
    public Note(@JsonProperty("project") Project project,
                @JsonProperty("path") Path path,
                @JsonProperty("note") String note,
                @JsonProperty("variant") Variant variant,
                @JsonProperty("blurSensitiveMedia") Boolean blurSensitiveMedia) {
        this.project = project;
        this.note = note;
        this.path = path;
        this.variant = variant;
        this.blurSensitiveMedia = blurSensitiveMedia;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Note note = (Note) o;
        return project.equals(note.project) &&
                path.equals(note.path);
    }

    @Override
    public int hashCode() { return Objects.hash(project, path);}
    @Override
    public String toString() {  return "Note{project=" + project.name + ", path=" + path + '}';}
}
