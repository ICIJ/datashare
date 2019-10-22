package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.icij.datashare.Entity;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.regex.Pattern;


public class Project implements Entity {
    private static final long serialVersionUID = 2568979856231459L;

    public final String name;
    public final Path sourcePath;
    @JsonIgnore
    public final String allowFromMask;
    @JsonIgnore
    private final Pattern pattern;

    public Project(String corpusName) {
        this(corpusName, Paths.get("/vault").resolve(corpusName), "*");
    }

    public Project(String corpusName, Path source, String allowFromMask) {
        name = corpusName;
        sourcePath = source;
        this.allowFromMask = allowFromMask;
        this.pattern = Pattern.compile(allowFromMask.
                replace(".", "\\.").
                replace("*", "\\d{1,3}"));
    }

    public Project(String corpusName, String allowFromMask) {
        this(corpusName, Paths.get("/vault").resolve(corpusName), allowFromMask);
    }

    public boolean isAllowed(final InetSocketAddress socketAddress) {
        return pattern.matcher(socketAddress.getAddress().getHostAddress()).matches();
    }

    public static boolean isAllowed(Project project, InetSocketAddress socketAddress) {
        return project == null || project.isAllowed(socketAddress);
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
