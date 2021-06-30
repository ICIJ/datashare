package org.icij.datashare.batch;

import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.nio.file.Path;
import java.util.Objects;

import static java.util.Optional.ofNullable;

public class BatchDownload {
    public final Project project;
    public final Path filename;
    public final User user;
    public final String queryString;

    public BatchDownload(final Project project, final Path filename, User user, String queryString) {
        this.project = ofNullable(project).orElseThrow(() -> new IllegalArgumentException("project cannot be null or empty"));
        this.user = ofNullable(user).orElseThrow(() -> new IllegalArgumentException("user cannot be null or empty"));
        this.queryString = ofNullable(queryString).orElseThrow(() -> new IllegalArgumentException("query cannot be null or empty"));
        this.filename = ofNullable(filename).orElseThrow(() -> new IllegalArgumentException("filename cannot be null or empty"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchDownload that = (BatchDownload) o;
        return Objects.equals(project, that.project) && Objects.equals(filename, that.filename) && Objects.equals(user, that.user) && Objects.equals(queryString, that.queryString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project, filename, user, queryString);
    }
}
