package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.web.IndexResource;

import java.io.Serial;
import java.lang.reflect.Method;

public class UserPolicyFilter implements Filter {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UserPolicyRepository userPolicyRepository;

    @Inject
    public UserPolicyFilter(UserPolicyRepository userPolicyRepository) {
        this.userPolicyRepository = userPolicyRepository;
    }

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier next) throws Exception {
        Policy annotation = findAnnotation(context);
        if(annotation == null) {
            return Payload.forbidden();
        }

        String project = context.get("project");
        if (annotation.admin()) {
            DatashareUser user = (DatashareUser) context.currentUser();

            if (user == null) {
                throw new UnauthorizedException();
            }

            UserPolicy userPolicy = userPolicyRepository.get(user, project);
            if ( userPolicy==null || !userPolicy.admin()) {
                throw new ForbiddenException();
            }
        }
        return next.get();
    }

    public static Policy findAnnotation(Context context) throws NoSuchMethodException {
        Method[] methods = IndexResource.class.getDeclaredMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Policy.class)) {
                return method.getAnnotation(Policy.class);
            }
        }
        return null;
    }
}
