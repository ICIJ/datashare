package org.icij.datashare;

import org.icij.datashare.text.Project;

import java.nio.file.Path;
import java.util.Objects;

public class Note {
    public enum Variant {
        dark, light, danger, info, success, warning, primary, secondary
    }
    public final Project project;
    public final String note;
    public final Path path;
    public final Variant variant;

    public Note(Project project, Path path, String note) {
        this(project, path, note, Variant.info);
    }

    public Note(Project project, Path path, String note, Variant variant) {
        this.project = project;
        this.note = note;
        this.path = path;
        this.variant = variant;
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
    public String toString() { return "Note{project=" + project.name + ", path=" + path + '}';}
}
