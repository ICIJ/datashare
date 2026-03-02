package org.icij.datashare.policies;

import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.UnauthorizedException;
import org.icij.datashare.session.DatashareUser;

import java.lang.annotation.Annotation;

public abstract class AbstractPolicyAnnotation<A extends Annotation> implements ApplyAroundAnnotation<A> {

    protected final Authorizer authorizer;

    protected AbstractPolicyAnnotation(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    protected DatashareUser requireUser(Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (user == null) {
            throw new UnauthorizedException();
        }
        return user;
    }

    protected boolean isAllowed(DatashareUser user, String projectId, Role role) {
        return authorizer.can(user.id, Domain.DEFAULT, projectId, role);
    }
}