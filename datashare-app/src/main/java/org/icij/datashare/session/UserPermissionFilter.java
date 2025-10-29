package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.Request;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import org.icij.datashare.user.UserPermissionRepository;
import org.icij.datashare.web.IndexResource;

import java.lang.reflect.Method;

public class UserPermissionFilter implements Filter {
    private final UserPermissionRepository userPermissionRepository;

    @Inject
    public UserPermissionFilter(UserPermissionRepository userPermissionRepository) {
        this.userPermissionRepository = userPermissionRepository;
    }

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier next) throws Exception {
        UserPermissionFilterAnn annotation = findAnnotation(context);
        if(annotation == null) {
            return Payload.forbidden();
        }
        if (annotation.admin()) {
            DatashareUser user = (DatashareUser) context.currentUser();

            if (user == null) {
                throw new SecurityException("No user logged in");
            }

            if ( !userPermissionRepository.get(user,"test").admin()) {
                throw new SecurityException("Admin access required");
            }
        }
        return next.get();
    }

    public static UserPermissionFilterAnn findAnnotation(Context context) throws NoSuchMethodException {
        Method[] methods = IndexResource.class.getDeclaredMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(UserPermissionFilterAnn.class)) {
                return method.getAnnotation(UserPermissionFilterAnn.class);
            }
        }
        return null;
    }
}
