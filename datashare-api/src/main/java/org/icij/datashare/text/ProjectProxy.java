package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.Entity;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING;
import static java.util.stream.Collectors.toList;


public class ProjectProxy implements Entity {
    private static final long serialVersionUID = 6220480617838134179L;

    public final String name;

    @JsonCreator(mode = DELEGATING)
    public ProjectProxy(@JsonProperty("name") String name){
        this.name  = name;
    }

    @JsonIgnore
    @Override
    public String getId() {
        return this.name;
    }

    public static ProjectProxy proxy(String projectName){
        return new ProjectProxy(projectName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectProxy project = (ProjectProxy) o;
        return name.equals(project.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    public static List<String> asNameList(List<ProjectProxy> projects){
        return projects.stream().map(ProjectProxy::getId).collect(toList());
    }

    public static List<ProjectProxy> fromNameStringList(List<String> projects){
        return projects.stream().map(ProjectProxy::proxy).collect(toList());
    }
    public static String[] asNameArray(List<ProjectProxy> projects){
        return  asNameList(projects).toArray(new String[0]);
    }
    public static String asCommaConcatNames(List<ProjectProxy> projects){
        return projects.stream().map(ProjectProxy::getId).collect(Collectors.joining(", "));
    }

}
