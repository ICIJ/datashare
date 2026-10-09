package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.policies.errors.InvalidValueException;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingService;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
import org.icij.datashare.utils.PayloadFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class MappingPolicyAnnotation implements ApplyAroundAnnotation<MappingPolicy> {
    private final Authorizer authorizer;
    private final ExtractionMappingService mappings;

    @Inject
    public MappingPolicyAnnotation(Authorizer authorizer, ExtractionMappingService mappings) {
        this.authorizer = authorizer;
        this.mappings = mappings;
    }

    @Override
    public Payload apply(MappingPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = Authorizer.requireUser((DatashareUser) context.currentUser());
        String projectId;
        try {
            projectId = Authorizer.requireValue(context.pathParam("project"), false);
        } catch (InvalidValueException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
        // Membership before the lookup: a 404 for an unknown id next to a 403 for a known one would
        // let a user with no role on the project list the mapping ids it holds.
        if (!can(user, projectId, Role.PROJECT_MEMBER)) {
            return Payload.forbidden();
        }
        Optional<ExtractionMapping> mapping;
        try {
            mapping = mappings.get(projectId, context.pathParam("mappingId"));
        } catch (UnreadableExtractionMapping e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.CONFLICT);
        }
        if (mapping.isEmpty()) {
            return Payload.notFound();
        }
        return can(user, projectId, requiredRole(mapping.get(), user)) ? payloadSupplier.apply(context) :
               Payload.forbidden();
    }

    // PROJECT_ADMIN inherits PROJECT_MEMBER, so the author needs the lower role and anyone else the higher one.
    private static Role requiredRole(ExtractionMapping mapping, DatashareUser user) {
        return Objects.equals(mapping.userId(), user.id) ? Role.PROJECT_MEMBER : Role.PROJECT_ADMIN;
    }

    private boolean can(DatashareUser user, String projectId, Role role) {
        return authorizer.can(user.id, Domain.DEFAULT, projectId, role);
    }
}
