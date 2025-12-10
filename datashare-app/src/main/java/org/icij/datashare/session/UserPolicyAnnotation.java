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

public class UserPolicyAnnotation implements ApplyAroundAnnotation<Policy> {

    private final UserPolicyVerifier userPolicyVerifier;

    @Inject
    public UserPolicyAnnotation(final UserPolicyVerifier userPolicyVerifier) throws URISyntaxException {
        this.userPolicyVerifier = userPolicyVerifier;
    }

    @Override
    public Payload apply(Policy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        String projectId = context.pathParam("index");
        DatashareUser user = (DatashareUser) context.currentUser();
        if (user == null) {
            throw new UnauthorizedException();
        }
        Optional<UserPolicy> policy = this.userPolicyVerifier.getUserPolicyByProject(user.id, projectId);
        if (policy.isEmpty()) {
            return Payload.forbidden();
        }
        return enforcePolicyRoles(annotation, policy.get()) ? payloadSupplier.apply(context) : Payload.forbidden();
    }

    private boolean enforcePolicyRoles(Policy annotation, UserPolicy userPolicy) {
        return Arrays.stream(annotation.roles()).allMatch(role ->
        {
            return userPolicyVerifier.enforce(userPolicy.userId(), userPolicy.projectId(), role.name());
        });
    }
}
