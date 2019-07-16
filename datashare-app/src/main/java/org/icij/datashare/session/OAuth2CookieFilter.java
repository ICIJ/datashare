package org.icij.datashare.session;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.builder.api.DefaultApi20;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Date;
import java.util.concurrent.ExecutionException;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.session.HashMapUser.fromJson;

public class OAuth2CookieFilter extends CookieAuthFilter {
    private final DefaultApi20 defaultOauthApi;
    private Logger logger = LoggerFactory.getLogger(getClass());

    public static final String REQUEST_CODE_KEY = "code";
    public static final String REQUEST_STATE_KEY = "state";

    private final Integer oauthTtl;
    private final String oauthApiUrl;
    private final String oauthSigninPath;
    private final String oauthCallbackPath;
    private final String oauthAuthorizeUrl;
    private final String oauthTokenUrl;
    private final String oauthClientId;
    private final String oauthClientSecret;

    @Inject
    public OAuth2CookieFilter(PropertiesProvider propertiesProvider, RedisUsers users, SessionIdStore sessionIdStore) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), users, sessionIdStore);
        this.oauthAuthorizeUrl = propertiesProvider.get("oauthAuthorizeUrl").orElse("http://localhost");
        this.oauthTokenUrl = propertiesProvider.get("oauthTokenUrl").orElse("http://localhost");
        this.oauthApiUrl = propertiesProvider.get("oauthApiUrl").orElse("http://localhost");
        this.oauthClientId = propertiesProvider.get("oauthClientId").orElse("");
        this.oauthClientSecret = propertiesProvider.get("oauthClientSecret").orElse("");
        this.oauthCallbackPath = propertiesProvider.get("oauthCallbackPath").orElse("/auth/callback");
        this.oauthSigninPath = propertiesProvider.get("oauthSigninPath").orElse("/auth/signin");
        this.oauthTtl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("600"));
        logger.info("created OAuth filter with redirectUrl={} clientId={} callbackPath={} uriPrefix={} loginPath={}",
                oauthAuthorizeUrl, oauthClientId, oauthCallbackPath, uriPrefix, oauthSigninPath);
        if (this.oauthCallbackPath.startsWith(this.oauthSigninPath)) {
            throw new IllegalStateException(format("oauthCallbackPath (%s) cannot start with oauthSigninPath (%s)", oauthCallbackPath, oauthSigninPath));
        }
        this.defaultOauthApi = new DefaultApi20() {
            @Override public String getAccessTokenEndpoint() { return oauthTokenUrl;}
            @Override protected String getAuthorizationBaseUrl() { return oauthAuthorizeUrl;}
        };
    }

    @Override
    protected Payload authenticationUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (uri.startsWith(oauthSigninPath) && "GET".equals(context.method())) {
            return this.signin(context);
        } else if (uri.startsWith(oauthCallbackPath)) {
            return this.callback(context);
        } else {
            return uri.startsWith("/auth/signout") && "GET".equals(context.method()) ? this.signout(context) : nextFilter.get();
        }
    }

    protected Payload callback(Context context) throws IOException, ExecutionException, InterruptedException {
        if (context.get(REQUEST_CODE_KEY) == null || context.get(REQUEST_STATE_KEY) == null || !"GET".equals(context.method()) ||
                sessionIdStore.getLogin(context.get(REQUEST_STATE_KEY)) == null) {
            return Payload.badRequest();
        }
        OAuth20Service service = new ServiceBuilder(oauthClientId).apiSecret(oauthClientSecret).
                callback(getCallbackUrl(context)).
                build(defaultOauthApi);
        OAuth2AccessToken accessToken = service.getAccessToken(context.get(REQUEST_CODE_KEY));

        final OAuthRequest request = new OAuthRequest(Verb.GET, oauthApiUrl);
        service.signRequest(accessToken, request);
        final Response oauthApiResponse = service.execute(request);

        HashMapUser user = fromJson(oauthApiResponse.getBody());
        redisUsers().createUser(user);
        return Payload.seeOther(this.validRedirectUrl(this.readRedirectUrlInCookie(context))).withCookie(this.authCookie(this.buildCookie(user, "/")));
    }

    @Override
    protected Payload signin(Context context) {
        try {
            return Payload.seeOther(oauthAuthorizeUrl + "?" +
                    format("client_id=%s&redirect_uri=%s&response_type=code&state=%s", oauthClientId, URLEncoder.encode(getCallbackUrl(context), "utf-8"), createState()));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private String getCallbackUrl(Context context) {
        return context.request().isSecure() ? "https://" : "http://"
                + context.request().header("Host") + this.oauthCallbackPath;
    }

    protected String createState() {
        String hexState = Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
        sessionIdStore.put(hexState, valueOf(new Date().getTime()));
        return hexState;
    }

    @Override protected Payload signout(Context context) {
        return super.signout(context);
    }
    @Override protected String cookieName() { return "_ds_session_id";}
    @Override protected int expiry() { return oauthTtl;}
    @Override protected boolean redirectToLogin(String uri) { return false;}

    private RedisUsers redisUsers() { return (RedisUsers) users;}
}
