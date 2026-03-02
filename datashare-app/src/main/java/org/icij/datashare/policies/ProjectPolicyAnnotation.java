package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;

import java.util.function.Function;


public class ProjectPolicyAnnotation extends AbstractPolicyAnnotation<ProjectPolicy> {

    @Inject
    public ProjectPolicyAnnotation(Authorizer authorizer) {
        super(authorizer);
    }

    @Override
    public Payload apply(ProjectPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {

        DatashareUser user = requireUser(context);

        String projectId = context.pathParam(annotation.idParam());
        if (projectId == null) {
            return Payload.forbidden();
        }

        if (!isAllowed(user, projectId, annotation.role())) {
            return Payload.forbidden();
        }

        return payloadSupplier.apply(context);
    }
}
