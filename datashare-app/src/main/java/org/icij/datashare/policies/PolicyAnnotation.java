package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;

import java.util.function.Function;

import static org.icij.datashare.policies.Authorizer.requireDomain;
import static org.icij.datashare.policies.Authorizer.requireValue;


public class PolicyAnnotation implements ApplyAroundAnnotation<Policy> {
    private final Authorizer authorizer;

    @Inject
    public PolicyAnnotation(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Payload apply(Policy annotation, Context context, Function<Context, Payload> payloadSupplier) {

        DatashareUser user = Authorizer.requireCurrentUser(context);

        String rawProjectId = context.pathParam(annotation.idParam());
        boolean instanceLevel = rawProjectId == null || rawProjectId.isBlank();

        //TODO #DOMAIN Currently Domain is not handled so we can't check it from query params
        Domain domain = instanceLevel ? Domain.of("*") : requireDomain(annotation.domain(), true);
        String projectId = instanceLevel ? "*" : requireValue(rawProjectId, true);

        if (!authorizer.can(user.id, domain, projectId, annotation.role())) {
            return Payload.forbidden();
        }

        return payloadSupplier.apply(context);
    }
}
