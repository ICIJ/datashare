package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.UserPolicy;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;


public class UserProjectPolicyAnnotation implements ApplyAroundAnnotation<ProjectPolicy> {

    private final UserPolicyVerifier userPolicyVerifier;

    @Inject
    public UserProjectPolicyAnnotation(final UserPolicyVerifier userPolicyVerifier) throws URISyntaxException {
        this.userPolicyVerifier = userPolicyVerifier;

    }

    @Override
    public Payload apply(ProjectPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (user == null) {
            throw new UnauthorizedException();
        }
        String projectId = context.pathParam(annotation.idParam());
        if (projectId == null) {
            return Payload.forbidden();
        }

        Optional<UserPolicy> policy = Optional.ofNullable(this.userPolicyVerifier.getUserPolicy(user.id, projectId));
        if (policy.isEmpty()) {
            return Payload.forbidden();
        }
        return enforcePolicyRoles(annotation, policy.get()) ? payloadSupplier.apply(context) : Payload.forbidden();
    }

    private boolean enforcePolicyRoles(ProjectPolicy annotation, UserPolicy userPolicy) {
        return Arrays.stream(annotation.roles()).allMatch(role -> userPolicyVerifier.enforce(userPolicy.userId(), userPolicy.projectId(), role.name()));
    }
}
