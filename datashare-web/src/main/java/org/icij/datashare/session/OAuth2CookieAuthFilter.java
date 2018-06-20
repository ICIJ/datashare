package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import okhttp3.*;
import org.icij.datashare.PropertiesProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Date;
import java.util.HashMap;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static net.codestory.http.convert.TypeConvert.fromJson;

public class OAuth2CookieAuthFilter extends CookieAuthFilter {
    private Logger logger = LoggerFactory.getLogger(getClass());

    public static final String REQUEST_CODE_KEY = "code";
    public static final String REQUEST_STATE_KEY = "state";

    private final String oauthClientSecret;
    private final String oauthApiUrl;
    private final String oauthLoginPath;
    private final String oauthCallbackPath;
    private final String oauthRedirectUrl;
    private final String oauthClientId;

    private OkHttpClient client = new OkHttpClient();

    @Inject
    public OAuth2CookieAuthFilter(PropertiesProvider propertiesProvider, RedisUsers users, SessionIdStore sessionIdStore) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), users, sessionIdStore);
        this.oauthRedirectUrl = propertiesProvider.get("oauthRedirectUrl").orElse("http://localhost");
        this.oauthApiUrl = propertiesProvider.get("oauthApiUrl").orElse("http://localhost");
        this.oauthClientId = propertiesProvider.get("oauthClientId").orElse("");
        this.oauthClientSecret = propertiesProvider.get("oauthClientSecret").orElse("");
        this.oauthCallbackPath = propertiesProvider.get("oauthCallbackPath").orElse("/auth/callback");
        this.oauthLoginPath = propertiesProvider.get("oauthLoginPath").orElse("/auth/login");
        logger.info("created OAuth filter with redirectUrl={} clientId={} callbackPath={} uriPrefix={} loginPath={}",
                oauthRedirectUrl, oauthClientId, oauthCallbackPath, uriPrefix, oauthLoginPath);
        if (this.oauthCallbackPath.startsWith(this.oauthLoginPath)) {
            throw new IllegalStateException(format("oauthCallbackPath (%s) cannot start with oauthLoginPath (%s)", oauthCallbackPath, oauthLoginPath));
        }
    }

    @Override
    protected Payload authenticationUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (uri.startsWith(oauthLoginPath) && "GET".equals(context.method())) {
            return this.signin(context);
        } else if (uri.startsWith(oauthCallbackPath)) {
            return this.callback(context);
        } else {
            return uri.startsWith("/auth/signout") && "GET".equals(context.method()) ? this.signout(context) : nextFilter.get();
        }
    }

    protected Payload callback(Context context) throws IOException {
        if (context.get(REQUEST_CODE_KEY) == null || context.get(REQUEST_STATE_KEY) == null || !"GET".equals(context.method()) ||
                sessionIdStore.getLogin(context.get(REQUEST_STATE_KEY)) == null) {
            return Payload.badRequest();
        }
        RequestBody formBody = new FormBody.Builder()
                .add("client_id", oauthClientId)
                .add("client_secret", oauthClientSecret)
                .add("code", context.get(REQUEST_CODE_KEY))
                .add("grant_type", "authorization_code")
                .add("redirect_uri", getCallbackUrl(context)).build();
        Response tokenResponse = client.newCall(new Request.Builder().url("http://xemx:3001/oauth/token").post(formBody).build()).execute();
        Response apiResponse = client.newCall(new Request.Builder().url(oauthApiUrl)
                .addHeader("Authorization", "Bearer " + fromJson(tokenResponse.body().string(), HashMap.class).get("access_token")).build()).execute();
        OAuth2User user = new OAuth2User(fromJson(apiResponse.body().string(), HashMap.class));
        redisUsers().createUser(user);
        return Payload.seeOther(this.validRedirectUrl(this.readRedirectUrlInCookie(context))).withCookie(this.authCookie(this.buildCookie(user, "/")));
    }

    @Override
    protected Payload signin(Context context) {
        return Payload.seeOther(oauthRedirectUrl + "?" +
                format("client_id=%s&redirect_uri=%s&response_type=code&state=%s", oauthClientId, getCallbackUrl(context), createState()));
    }

    @NotNull
    private String getCallbackUrl(Context context) {
        try {
            return URLEncoder.encode(context.request().isSecure() ? "https://" : "http://"
                    + context.request().header("Host") + this.oauthCallbackPath, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    protected String createState() {
        String hexState = Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
        sessionIdStore.put(hexState, valueOf(new Date().getTime()));
        return hexState;
    }

    @Override
    protected Payload signout(Context context) {
        return super.signout(context);
    }

    @Override
    protected String cookieName() { return "_ds_session_id";}
    private RedisUsers redisUsers() { return (RedisUsers) users;}
}
