package org.icij.datashare.tabular;

import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.tabular.DuplicateExtractionMapping.Reason;
import org.icij.datashare.user.User;
import java.util.Objects;
import java.util.Optional;

public class ExtractionMappingServiceImpl implements ExtractionMappingService {
    private final ExtractionMappingRepository mappings;
    private final Authorizer authorizer;

    /** Without an {@link Authorizer}, project roles are not enforced: local modes grant none. */
    public ExtractionMappingServiceImpl(ExtractionMappingRepository mappings) {
        this(mappings, null);
    }

    public ExtractionMappingServiceImpl(ExtractionMappingRepository mappings, Authorizer authorizer) {
        this.mappings = mappings;
        this.authorizer = authorizer;
    }

    @Override
    public void save(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        if (!mappings.save(mapping)) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id(), Reason.ID_TAKEN);
        }
    }

    @Override
    public boolean saveIfIdentical(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        if (mappings.save(mapping)) {
            return true;
        }
        if (!isStored(mapping)) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id(), Reason.DIFFERENT_DEFINITION);
        }
        return false;
    }

    // A CLI run carries nullUser(), which holds no role but is trusted with the whole database.
    @Override
    public Optional<ExtractionMapping> get(String projectId, String id, User requester) {
        if (authorizer == null || requester.isNull()) {
            return mappings.get(projectId, id);
        }
        requireRole(requester, projectId, id, Role.PROJECT_MEMBER);
        Optional<ExtractionMapping> mapping = readAs(requester, projectId, id);
        String ownerId = mapping.map(ExtractionMapping::userId).orElse(null);
        requireRole(requester, projectId, id, requiredRole(ownerId, requester));
        return mapping;
    }

    private Optional<ExtractionMapping> readAs(User requester, String projectId, String id) {
        try {
            return mappings.get(projectId, id);
        } catch (UnreadableExtractionMapping e) {
            requireRole(requester, projectId, id, requiredRole(e.userId, requester));
            throw e;
        }
    }

    private void requireRole(User requester, String projectId, String id, Role role) {
        if (!authorizer.can(requester.id, Domain.DEFAULT, projectId, role)) {
            throw new ForbiddenExtractionMapping(projectId, id, requester.id);
        }
    }

    // PROJECT_ADMIN inherits PROJECT_MEMBER, so the author needs the lower role and anyone else the higher one.
    // An unknown id has no author, so only an admin learns that it is unknown.
    private static Role requiredRole(String ownerId, User requester) {
        return Objects.equals(ownerId, requester.id) ? Role.PROJECT_MEMBER : Role.PROJECT_ADMIN;
    }

    // A stored definition that no longer reads cannot be the caller's, so it stays a conflict.
    private boolean isStored(ExtractionMapping mapping) throws DuplicateExtractionMapping {
        try {
            return mappings.get(mapping.projectId(), mapping.id()).filter(mapping::equals).isPresent();
        } catch (UnreadableExtractionMapping e) {
            throw new DuplicateExtractionMapping(mapping.projectId(), mapping.id(), Reason.UNREADABLE_DEFINITION);
        }
    }
}
