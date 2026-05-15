package org.icij.datashare.project.admin;

public record ProjectDeleteOptions(boolean keepIndex) {
    public static ProjectDeleteOptions defaults() {
        return new ProjectDeleteOptions(false);
    }
}
