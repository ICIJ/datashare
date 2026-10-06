package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class MappingPolicyAnnotation implements ApplyAroundAnnotation<MappingPolicy> {
    private final Authorizer authorizer;
    private final ExtractionMappingRepository mappings;

    @Inject
    public MappingPolicyAnnotation(Authorizer authorizer, ExtractionMappingRepository mappings) {
        this.authorizer = authorizer;
        this.mappings = mappings;
    }

    @Override
    public Payload apply(MappingPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = Authorizer.requireUser((DatashareUser) context.currentUser());
        String projectId = Authorizer.requireValue(context.pathParam("project"), false);
        Optional<ExtractionMapping> mapping = mappings.get(projectId, context.pathParam("mappingId"));
        if (mapping.isEmpty()) {
            return Payload.notFound();
        }
        boolean isAllowed = authorizer.can(user.id, Domain.DEFAULT, projectId, Role.PROJECT_ADMIN);
        boolean isOwner = Objects.equals(mapping.get().userId(), user.id);
        boolean canAsOwner = isOwner && authorizer.can(user.id, Domain.DEFAULT, projectId, Role.PROJECT_MEMBER);
        return isAllowed || canAsOwner ? payloadSupplier.apply(context) : Payload.forbidden();
    }
}
