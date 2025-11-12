package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.user.UserPolicyVerifier;

import java.net.URISyntaxException;
import java.util.function.Function;

import static org.icij.datashare.text.Project.project;

public class UserPolicyAnnotation implements ApplyAroundAnnotation<Policy> {

    private final UserPolicyRepository jooqRepository;
    private final UserPolicyVerifier userPolicyVerifier;

    @Inject
    public UserPolicyAnnotation(final UserPolicyRepository userPolicyRepository) throws URISyntaxException {
        this.jooqRepository = userPolicyRepository;
        this.userPolicyVerifier = UserPolicyVerifier.getInstance(userPolicyRepository);
    }

    @Override
    public Payload apply(Policy policy, Context context, Function<Context, Payload> function) {
        if(authorize((DatashareUser) context.currentUser(), project("test-datashare"))){
            return function.apply(context);
        }
        return Payload.forbidden();
    }


    // TODO to be called by apply function
    public boolean authorize(DatashareUser user, Project project){
        if(user == null || project == null) throw new UnauthorizedException();
        return this.enforce(user, project);
    }

    private boolean enforce(DatashareUser user, Project project){
        UserPolicy userPolicy = jooqRepository.get(user, project.getId());
        return userPolicyVerifier.enforce(userPolicy);
    }
}
