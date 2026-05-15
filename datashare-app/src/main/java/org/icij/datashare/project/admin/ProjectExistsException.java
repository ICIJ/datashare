package org.icij.datashare.project.admin;

public class ProjectExistsException extends Exception {
    public ProjectExistsException(String name) {
        super("project '" + name + "' already exists");
    }
}
