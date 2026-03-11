package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;

import javax.annotation.Nullable;

public class BasicAuthAdaptorFilter extends BasicAuthFilter {
    String AUTH_PATH = "/auth";
    String AUTH_SIGNOUT_PATH = AUTH_PATH + "/signout";
    String COOKIE_NAME = "_ds_session_id";
    @Nullable
    private final PostLoginEnroller postLoginEnroller;

    @Inject
    public BasicAuthAdaptorFilter(PropertiesProvider propertiesProvider, UsersWritable users,
                                  @Nullable PostLoginEnroller postLoginEnroller) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), "datashare", users);
        this.postLoginEnroller = postLoginEnroller;
    }

    @Override
    public boolean matches(String uri, Context context) {
        return super.matches(uri, context) || uri.startsWith(AUTH_PATH);
    }

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        // The sign-out URL always returns unauthorized which can be used to invalidate
        // the browser's basic auth cache.
        if (uri.startsWith(AUTH_SIGNOUT_PATH)) {
            return Payload.unauthorized("datashare").withCookie(this.emptyAuthCookie());
        }
        // Otherwise, redirect all auth paths to home
        if (uri.startsWith(AUTH_PATH)) {
            return Payload.temporaryRedirect("/");
        }
        // We ensure the auth cookie is deleted
        Payload payload = super.apply(uri, context, nextFilter).withCookie(this.emptyAuthCookie());
        if (postLoginEnroller != null && context.currentUser() instanceof DatashareUser user) {
            postLoginEnroller.enroll(user);
        }
        return payload;
    }

    protected NewCookie emptyAuthCookie() {
        NewCookie cookie = new NewCookie(COOKIE_NAME, "", "/", true);
        cookie.setExpiry(0);
        cookie.setDomain(null);
        cookie.setSecure(false);
        return cookie;
    }
}
