package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;

import java.util.function.Function;

import static org.icij.datashare.policies.Authorizer.requireDomain;
import static org.icij.datashare.policies.Authorizer.requireIdParam;


public class ProjectPolicyAnnotation implements ApplyAroundAnnotation<ProjectPolicy> {
    private final Authorizer authorizer;

    @Inject
    public ProjectPolicyAnnotation(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Payload apply(ProjectPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {

        DatashareUser user = Authorizer.requireCurrentUser(context);

        //TODO #DOMAIN Currently Domain is not handled so we can't check it from query params
        Domain domain = requireDomain(annotation.domain(), true);

        String projectId = requireIdParam(context, annotation.idParam());

        if (!authorizer.can(user.id, domain, projectId, annotation.role())) {
            return Payload.forbidden();
        }

        return payloadSupplier.apply(context);
    }
}
