package org.icij.datashare.project.admin;

import java.io.IOException;

public interface ProjectAdminService {

    /**
     * Creates a project row, optionally creates the ES index, throws if the
     * project already exists.
     */
    ProjectCreated create(ProjectCreateRequest request)
            throws ProjectExistsException, ValidationException, IOException;

    /**
     * Idempotent counterpart of {@link #create}: if the project already exists,
     * returns a {@code ProjectCreated} with {@code noop=true} populated from
     * the existing row.
     */
    ProjectCreated createIfNotExists(ProjectCreateRequest request)
            throws ValidationException, IOException;

    /**
     * Returns indexed-document count + member count for the named project.
     * Throws if the project row is missing. Used by the CLI confirmation prompt.
     */
    ProjectStats stats(String name) throws ProjectNotFoundException, IOException;

    /**
     * Deletes the project and every dependent resource (ES index unless
     * {@link ProjectDeleteOptions#keepIndex()}, queues, report map, artifact dir).
     * Throws if the project row is missing.
     */
    ProjectDeleted delete(String name, ProjectDeleteOptions options)
            throws ProjectNotFoundException, IOException;

    /**
     * Idempotent counterpart of {@link #delete}: returns a noop result when the
     * project is already missing.
     */
    ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException;
}
