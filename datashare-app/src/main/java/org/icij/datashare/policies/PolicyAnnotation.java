package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.DatashareUser;

import java.util.function.Function;


public class PolicyAnnotation implements ApplyAroundAnnotation<Policy> {

    private final Authorizer authorizer;

    @Inject
    public PolicyAnnotation(final Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public Payload apply(Policy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (user == null) {
            throw new UnauthorizedException();
        }
        String projectId = context.pathParam(annotation.idParam());
        if (projectId == null) {
            return Payload.forbidden();
        }

        if (!authorizer.can(user.id, Domain.of(""), projectId, annotation.role())) {
            return Payload.forbidden();
        }
        return payloadSupplier.apply(context);
    }


}
