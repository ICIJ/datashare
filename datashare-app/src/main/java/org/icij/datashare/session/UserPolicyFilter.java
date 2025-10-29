package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.UserPolicyRepository;

import java.util.function.Function;

public class UserPolicyFilter implements ApplyAroundAnnotation<Policy> {

    private final UserPolicyRepository jooqRepository;

    @Inject
    public UserPolicyFilter(final UserPolicyRepository jooqRepository) {
        this.jooqRepository = jooqRepository;
    }

    @Override
    public Payload apply(Policy policy, Context context, Function<Context, Payload> function) {
        return function.apply(context);
    }
}
