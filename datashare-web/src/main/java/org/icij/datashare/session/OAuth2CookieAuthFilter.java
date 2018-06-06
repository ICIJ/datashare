package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;

public class OAuth2CookieAuthFilter extends CookieAuthFilter {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final String oauthLoginPath;
    private final String oauthCallbackPath;
    private final String oauthRedirectUrl;
    private final String oauthClientId;

    @Inject
    public OAuth2CookieAuthFilter(PropertiesProvider propertiesProvider, Users users, SessionIdStore sessionIdStore) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), users, sessionIdStore);
        this.oauthLoginPath = propertiesProvider.get("oauthLoginPath").orElse("/auth/login");
        this.oauthCallbackPath = propertiesProvider.get("oauthCallbackPath").orElse("/auth/callback");
        this.oauthRedirectUrl = propertiesProvider.get("oauthRedirectUrl").orElse("http://localhost");
        this.oauthClientId = propertiesProvider.get("oauthClientId").orElse("");
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
        String myHost = context.request().isSecure() ? "https://" : "http://"
                + context.request().header("Host") + this.oauthCallbackPath;
        return Payload.seeOther(oauthRedirectUrl + "?" +
                format("client_id=%s&redirect_uri=%s&response_type=code&state=%s", oauthClientId, myHost, createState()));
    }

    protected String createState() {
        return Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
    }

    @Override
    protected Payload signout(Context context) {
        return super.signout(context);
    }
}
