package org.icij.datashare.project.admin;

import java.io.IOException;
import java.util.List;

public interface ProjectAdminService {
    /**
     * Creates a project row, optionally creates the ES index, throws if the
     * project already exists.
     */
    ProjectCreated create(ProjectCreateRequest request) throws ProjectExistsException, ValidationException, IOException;

    /**
     * Idempotent counterpart of {@link #create}: if the project already exists,
     * returns a {@code ProjectCreated} with {@code noop=true} populated from
     * the existing row.
     */
    ProjectCreated createIfNotExists(ProjectCreateRequest request) throws ValidationException, IOException;

    /**
     * Returns indexed-document count + member count for the named project.
     * Throws if the project row is missing. Used by the CLI confirmation prompt.
     *
     * <p>When {@code includeIndexCount} is {@code false}, {@code indexedDocuments}
     * on the returned {@code ProjectStats} is {@link java.util.OptionalLong#empty()}
     * and no Elasticsearch round-trip happens. Pass {@code false} when the caller
     * plans to skip the index in the cascade (e.g. {@code --keep-index}).
     */
    ProjectStats stats(String name, boolean includeIndexCount) throws ProjectNotFoundException, IOException;

    /**
     * Deletes the project and every dependent resource (ES index unless
     * {@link ProjectDeleteOptions#keepIndex()}, queues, report map, artifact dir).
     * Throws if the project row is missing.
     */
    ProjectDeleted delete(String name, ProjectDeleteOptions options) throws ProjectNotFoundException, IOException;

    /**
     * Idempotent counterpart of {@link #delete}: returns a noop result when the
     * project is already missing.
     */
    ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException;

    /**
     * Grants {@code role} on the named project to the named user, replacing
     * any existing project role. Appends {@code projectName} to the user's
     * {@code groups_by_applications.datashare} list, deletes every existing
     * project grouping policy for the user, then writes the new Casbin
     * grouping policy {@code g <userLogin> <role> default::<projectName>}.
     *
     * @throws ValidationException if {@code role} is not a {@code PROJECT_*} role.
     * @throws ProjectNotFoundException if the project row is missing.
     * @throws UserNotFoundException if the user is missing.
     * @throws WideRoleHeldException if the user holds an instance or domain admin role, which replaces
     *                               project roles.
     */
    ProjectGranted grant(String projectName, String userLogin, org.icij.datashare.policies.Role role) throws
            ProjectNotFoundException, UserNotFoundException, ValidationException, WideRoleHeldException;

    /**
     * Idempotent counterpart of {@link #grant}: returns {@code noop=true}
     * when the user already holds exactly the requested role and no other
     * project roles.
     */
    ProjectGranted grantIfNotExists(String projectName, String userLogin, org.icij.datashare.policies.Role role) throws
            ProjectNotFoundException, UserNotFoundException, ValidationException, WideRoleHeldException;

    /**
     * Removes every project-scoped Casbin grouping policy the user holds on
     * the project, and prunes {@code projectName} from the user's
     * {@code groups_by_applications.datashare} list.
     *
     * @throws ProjectNotFoundException if the project row is missing.
     * @throws UserNotFoundException if the user is missing.
     */
    ProjectRevoked revoke(String projectName, String userLogin) throws ProjectNotFoundException, UserNotFoundException;

    /**
     * Idempotent counterpart of {@link #revoke}: returns {@code noop=true}
     * when the user does not exist or holds no roles on the project. Still
     * throws {@link ProjectNotFoundException} when the project is missing.
     *
     * <p>Unlike {@link #revoke}, this method does <strong>not</strong> throw
     * {@link UserNotFoundException}; a missing user is converted to a noop
     * result. Only a missing project propagates as an exception.
     */
    ProjectRevoked revokeIfExists(String projectName, String userLogin) throws ProjectNotFoundException;

    /**
     * Appends every name in {@code projectNames} to the user's {@code groups_by_applications.datashare}
     * list, without writing any Casbin policy. Used for an instance or domain admin wide-role grant:
     * authorization already comes from the wildcard role, this only affects which projects the user's
     * inventory (and so the UI) lists for them.
     *
     * @throws UserNotFoundException if the user is missing.
     */
    void addProjectsToInventory(List<String> projectNames, String userLogin) throws UserNotFoundException;

    /**
     * Removes every name in {@code projectNames} from the user's {@code groups_by_applications.datashare}
     * list, without touching any Casbin policy. Counterpart of {@link #addProjectsToInventory}, used
     * when an instance or domain admin wide-role grant is revoked. A project the user still holds a
     * per-project role on is kept, since that role still authorizes it.
     *
     * @throws UserNotFoundException if the user is missing.
     */
    void removeProjectsFromInventory(List<String> projectNames, String userLogin) throws UserNotFoundException;

    /**
     * Adds every existing project to the inventory of each user holding an instance admin or default
     * domain admin role, like {@link #addProjectsToInventory} does on a wide-role grant, and deletes
     * their project roles, which a wide role replaces (plus the domain admin roles of an instance
     * admin). Covers admins granted before those rules and
     * projects created since their grant. Idempotent; a role held by a user that no longer exists is
     * skipped.
     */
    void backfillWideAdminInventories();

    /**
     * Adds a newly created project to the inventory of each wide admin (see
     * {@link #backfillWideAdminInventories}), so they list it without waiting for the next startup
     * backfill. A failure is logged, not thrown: the project itself is already created.
     */
    void addProjectToWideAdminInventories(String projectName);
}
