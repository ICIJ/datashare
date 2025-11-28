package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyVerifier;
import org.icij.datashare.user.UserRepository;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.function.Function;

import static org.icij.datashare.text.Project.project;

public class UserPolicyAnnotation implements ApplyAroundAnnotation<Policy> {

    private final UserPolicyVerifier userPolicyVerifier;

    @Inject
    public UserPolicyAnnotation(final UserRepository userPolicyRepository) throws URISyntaxException {
        this.userPolicyVerifier = UserPolicyVerifier.getInstance(userPolicyRepository);
    }

    @Override
    public Payload apply(Policy annotation, Context context, Function<Context, Payload> payloadSupplier) {

        String index = context.pathParam("index");
        DatashareUser user = (DatashareUser) context.currentUser();
        Project project = project(index); //check if project exists ?
        if (user == null) throw new UnauthorizedException();
        return user.getPolicy(project.getId())
                .map(p -> enforcePolicyRoles(annotation, p) ? payloadSupplier.apply(context) : null)
                .orElse(Payload.forbidden());

    }

    private boolean enforcePolicyRoles(Policy annotation, UserPolicy userPolicy) {
        return Arrays.stream(annotation.roles()).allMatch(role ->
                userPolicyVerifier.enforce(userPolicy.userId(), userPolicy.projectId(), role.name()));
    }
}
