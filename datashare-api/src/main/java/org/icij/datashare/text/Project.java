package org.icij.datashare.text;

import org.icij.datashare.Entity;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;


public class Project implements Entity {
    private static final long serialVersionUID = 2568979856231459L;

    private final String name;
    private final Path sourcePath;

    public Project(String corpusName) {
        this(corpusName, Paths.get("/vault").resolve(corpusName));
    }

    public Project(String corpusName, Path source) {
        name = corpusName;
        sourcePath = source;
    }

    @Override
    public String getId() {
        return name;
    }
    public String getName() {
        return name;
    }
    public Path getSourcePath() {
        return sourcePath;
    }
    public static Project project(final String projectName) {
        return new Project(projectName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Project project = (Project) o;
        return name.equals(project.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
