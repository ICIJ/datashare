package org.icij.datashare.user;

import java.util.Collection;
import java.util.Set;

/**
 * The projects a request may reach, on top of what its user is granted. "Every project" and "no project" are two
 * distinct values, so an empty list can never read as unrestricted.
 */
public sealed interface ProjectScope {
    ProjectScope ALL = new All();

    boolean allows(String projectId);

    default boolean allowsAll(Collection<String> projectIds) {
        return projectIds.stream().allMatch(this::allows);
    }

    record All() implements ProjectScope {
        @Override
        public boolean allows(String projectId) {
            return true;
        }
    }

    record Only(Set<String> projectIds) implements ProjectScope {
        public Only {
            projectIds = Set.copyOf(projectIds);
        }

        // "*" is never allowed: instance-wide and domain-wide checks are refused
        @Override
        public boolean allows(String projectId) {
            return projectId != null && !"*".equals(projectId) && projectIds.contains(projectId);
        }

        // naming no project at all asks for something that is not tied to a project
        @Override
        public boolean allowsAll(Collection<String> ids) {
            return !ids.isEmpty() && ProjectScope.super.allowsAll(ids);
        }
    }
}
