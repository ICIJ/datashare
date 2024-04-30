package org.icij.datashare.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.builder.api.DefaultApi20;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.errors.BadRequestException;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;

/**
 * This class is responsible for OAuth2 authentication.
 * If you need to add custom processing for saved user session
 * feel free to add a withYourParams() method. see {@link #processOAuthApiResponse(Response) processOAuthApiResponse}
 */
@Singleton
public final class OAuth2CookieFilter extends CookieAuthFilter {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public static final String REQUEST_CODE_KEY = "code";
    public static final String REQUEST_STATE_KEY = "state";

    private final DefaultApi20 defaultOauthApi;
    private final Integer oauthTtl;
    private final String oauthApiUrl;
    private final String oauthSigninPath;
    private final String oauthCallbackPath;
    private final String oauthAuthorizeUrl;
    private final String oauthTokenUrl;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final String oauthDefaultProject;
    private final String oauthScope;
    private final String oauthClaimIdAttribute;

    @Inject
    public OAuth2CookieFilter(PropertiesProvider propertiesProvider, UsersWritable users, SessionIdStore sessionIdStore) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), users, sessionIdStore);
        this.oauthAuthorizeUrl = propertiesProvider.get("oauthAuthorizeUrl").orElse("http://localhost");
        this.oauthTokenUrl = propertiesProvider.get("oauthTokenUrl").orElse("http://localhost");
        this.oauthApiUrl = propertiesProvider.get("oauthApiUrl").orElse("http://localhost");
        this.oauthClientId = propertiesProvider.get("oauthClientId").orElse("");
        this.oauthClientSecret = propertiesProvider.get("oauthClientSecret").orElse("");
        this.oauthCallbackPath = propertiesProvider.get("oauthCallbackPath").orElse("/auth/callback");
        this.oauthSigninPath = propertiesProvider.get("oauthSigninPath").orElse("/auth/signin");
        this.oauthTtl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("600"));
        this.oauthDefaultProject = propertiesProvider.get("oauthDefaultProject").orElse("");
        this.oauthClaimIdAttribute = propertiesProvider.get("oauthClaimIdAttribute").orElse("");
        this.oauthScope = propertiesProvider.get("oauthScope").orElse("");
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

    @Override
    public boolean matches(String uri, Context context) {
        return super.matches(uri, context) || uri.isEmpty() || uri.equals("/");
    }

    @Override
    protected Payload otherUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (context.currentUser() != null) {
            return nextFilter.get();
        }

        String sessionId = readSessionIdInCookie(context);
        if(uri.equals("/") || uri.isEmpty()) {
            if (sessionId != null) {
                String login = sessionIdStore.getLogin(sessionId);
                if (login != null) {
                    User user = users.find(login);
                    context.setCurrentUser(user);
                }
            }
            return nextFilter.get();
        }
        return super.otherUri(uri, context, nextFilter);
    }

    private Payload callback(Context context) throws IOException, ExecutionException, InterruptedException {
        logger.info("callback called with {}={} {}={}", REQUEST_CODE_KEY, context.get(REQUEST_CODE_KEY), REQUEST_STATE_KEY, context.get(REQUEST_STATE_KEY));
        if (context.get(REQUEST_CODE_KEY) == null || context.get(REQUEST_STATE_KEY) == null || !"GET".equals(context.method()) ||
                sessionIdStore.getLogin(context.get(REQUEST_STATE_KEY)) == null) {
            return Payload.badRequest();
        }
        OAuth20Service service = new ServiceBuilder(oauthClientId).apiSecret(oauthClientSecret).
                callback(getCallbackUrl(context)).
                build(defaultOauthApi);

        logger.info("getting an access token from {} and code value", service);
        OAuth2AccessToken accessToken = service.getAccessToken(context.get(REQUEST_CODE_KEY));

        final OAuthRequest request = new OAuthRequest(Verb.GET, oauthApiUrl);
        service.signRequest(accessToken, request);
        logger.info("sending request to user API signed with the token : {}", request);
        final Response oauthApiResponse = service.execute(request);

        logger.info("received response code from user API : {}", oauthApiResponse.getCode());
        DatashareUser datashareUser = processOAuthApiResponse(oauthApiResponse);
        return Payload.seeOther(this.validRedirectUrl(this.readRedirectUrlInCookie(context))).withCookie(this.authCookie(this.buildCookie(datashareUser, "/")));
    }

    private DatashareUser processOAuthApiResponse(Response oauthApiResponse) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(oauthApiResponse.getBody());
        Map<String, Object> userMap = mapper.convertValue(root, new TypeReference<>() {});
        if (!oauthClaimIdAttribute.isEmpty()){
            if (userMap.get(oauthClaimIdAttribute) == null) {
                logger.error("The attribute {} does not exist in the response body.", oauthClaimIdAttribute);
                throw new BadRequestException();
            }
            String id = ofNullable(root.get(oauthClaimIdAttribute)).map(JsonNode::asText).orElse(null);
            userMap.put("id", id);
            userMap.put("uid", id);
        }
        if (!oauthDefaultProject.isEmpty()) {
            userMap.put("provider", "icij");
            userMap.put("groups_by_applications", Map.of("datashare", singletonList(oauthDefaultProject)));
        }
        DatashareUser datashareUser = new DatashareUser(userMap);
        writableUsers().saveOrUpdate(datashareUser);
        return datashareUser;
    }

    @Override
    protected Payload signin(Context context) {
        String oauthAuthorizeQS = format("?client_id=%s&redirect_uri=%s&response_type=code&state=%s", oauthClientId, URLEncoder.encode(getCallbackUrl(context), StandardCharsets.UTF_8), createState());
        if (!Objects.equals(oauthScope, "")) {
            oauthAuthorizeQS = oauthAuthorizeQS + "&scope=" + oauthScope;
        }
        return Payload.seeOther(oauthAuthorizeUrl + oauthAuthorizeQS);
    }

    private String getCallbackUrl(Context context) {
        String host = ofNullable(context.request().header("x-forwarded-host")).orElse(context.request().header("Host"));
        String proto = ofNullable(context.request().header("x-forwarded-proto")).orElse(context.request().isSecure() ? "https" : "http");
        String url = proto + "://" + host + this.oauthCallbackPath;
        logger.info("oauth callback url = {}",url);
        return url;
    }

    private String createState() {
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
    private UsersWritable writableUsers() { return (UsersWritable) users;}

    private static class Datashare {

    }
}
