package org.icij.datashare.project.admin;

public class ProjectNotFoundException extends Exception {
    public ProjectNotFoundException(String name) {
        super("project '" + name + "' not found");
    }
}
