package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;

import java.util.function.Function;

import static org.icij.datashare.policies.Authorizer.requireDomain;
import static org.icij.datashare.policies.Authorizer.requireIdParam;


public class PolicyAnnotation implements ApplyAroundAnnotation<Policy> {
    private final Authorizer authorizer;

    @Inject
    public PolicyAnnotation(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Payload apply(Policy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = Authorizer.requireUser((DatashareUser) context.currentUser());

        Domain domain;
        String projectId;
        if (annotation.role() == Role.INSTANCE_ADMIN) {
            domain = Domain.of("*");
            projectId = "*";
        } else {
            domain = requireDomain(annotation.domain(), true);
            projectId = annotation.role() == Role.DOMAIN_ADMIN ? "*" : requireIdParam(context, annotation.idParam());
        }

        if (!authorizer.can(user.id, domain, projectId, annotation.role())) {
            return Payload.forbidden();
        }

        return payloadSupplier.apply(context);
    }
}
