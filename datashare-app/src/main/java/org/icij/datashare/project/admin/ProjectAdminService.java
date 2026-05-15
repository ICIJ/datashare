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
     *
     * <p>When {@code includeIndexCount} is {@code false}, the {@code indexedDocuments}
     * field on the returned {@code ProjectStats} is {@link ProjectStats#INDEX_CHECK_SKIPPED}
     * and no Elasticsearch round-trip happens. Pass {@code false} when the caller plans to
     * skip the index in the cascade (e.g. {@code --keep-index}).
     */
    ProjectStats stats(String name, boolean includeIndexCount) throws ProjectNotFoundException, IOException;

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

    /**
     * Grants {@code Role.PROJECT_ADMIN} on the named project to the named user.
     * Appends the project to the user's {@code groups_by_applications.datashare}
     * list in {@code user_inventory.details} and adds a casbin grouping policy
     * {@code g <userLogin> PROJECT_ADMIN datashare::<projectName>}.
     *
     * @return {@code true} if the grant was applied, {@code false} if the user
     *         does not exist in the user inventory (callers may treat this as a
     *         soft failure for auto-grant in single-user dev setups).
     * @throws ProjectNotFoundException if the project row is missing.
     */
    boolean addAdminToProject(String projectName, String userLogin)
            throws ProjectNotFoundException;
}
