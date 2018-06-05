package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;

public class OAuth2CookieAuthFilter extends CookieAuthFilter {
    private final String oauthLoginPath;
    private final String oauthCallbackPath;
    private final String oauthRedirectUrl;

    OAuth2CookieAuthFilter(PropertiesProvider propertiesProvider, Users users, SessionIdStore sessionIdStore) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), users, sessionIdStore);
        this.oauthLoginPath = propertiesProvider.get("oauthLoginPath").orElse("/auth/login");
        this.oauthCallbackPath = propertiesProvider.get("oauthCallbackPath").orElse("/auth/callback");
        this.oauthRedirectUrl = propertiesProvider.get("oauthRedirectUrl").orElse("http://localhost");
    }

    @Override
    protected Payload authenticationUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (uri.startsWith(oauthLoginPath) && "GET".equals(context.method())) {
            return this.signin(context);
        } else {
            return uri.startsWith("/auth/signout") && "GET".equals(context.method()) ? this.signout(context) : nextFilter.get();
        }
    }

    @Override
    protected Payload signin(Context context) {
        return Payload.seeOther(oauthRedirectUrl);
    }

    @Override
    protected Payload signout(Context context) {
        return super.signout(context);
    }
}
