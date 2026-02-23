package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.policies.Domain;

import java.net.URISyntaxException;
import java.util.function.Function;


public class AuthorizationAnnotation implements ApplyAroundAnnotation<Policy> {

    private final Authorizer authorizer;

    @Inject
    public AuthorizationAnnotation(final Authorizer authorizer) throws URISyntaxException {
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
