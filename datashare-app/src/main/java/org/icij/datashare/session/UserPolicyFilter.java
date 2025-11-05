package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.user.UserPolicyVerifier;

import javax.ws.rs.ForbiddenException;
import java.util.function.Function;

public class UserPolicyFilter implements ApplyAroundAnnotation<Policy> {

    private final UserPolicyRepository jooqRepository;
    private final UserPolicyVerifier userPolicyVerifier;

    @Inject
    public UserPolicyFilter(final UserPolicyRepository userPolicyRepository) {
        this.jooqRepository = userPolicyRepository;
        this.userPolicyVerifier = UserPolicyVerifier.getInstance(userPolicyRepository);
    }

    @Override
    public Payload apply(Policy policy, Context context, Function<Context, Payload> function) {
        return function.apply(context);
    }


    // TODO to be called by apply function
    public boolean authorize(DatashareUser user, Project project){
        if(user == null || project == null) throw new UnauthorizedException();
        if(!this.enforce(user,project)){
            throw new ForbiddenException();
        }
        return true;
    }

    private boolean enforce(DatashareUser user, Project project){
        UserPolicy userPolicy = jooqRepository.get(user, project.getId());
        return userPolicyVerifier.enforce(userPolicy);
    }
}
