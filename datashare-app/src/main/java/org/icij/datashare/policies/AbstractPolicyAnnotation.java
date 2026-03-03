package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;

import java.lang.annotation.Annotation;

public abstract class AbstractPolicyAnnotation<A extends Annotation> implements ApplyAroundAnnotation<A> {

    protected final Authorizer authorizer;

    protected AbstractPolicyAnnotation(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    protected static String requireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new ForbiddenException();
        }
        return value;
    }

    protected static DatashareUser requireUser(Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (user == null) {
            throw new UnauthorizedException();
        }
        return user;
    }

    protected static Domain requireDomain(String domain) {
        return Domain.of(requireValue(domain));
    }

    protected static String requireIdParam(Context context, String idParam) {
        return requireValue(context.pathParam(idParam));
    }

    protected boolean isNotAllowed(DatashareUser user, Domain domain, String projectId, Role role) {
        return !authorizer.can(user.id, domain, projectId, role);
    }
}