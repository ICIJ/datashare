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
import net.codestory.http.NewCookie;
import net.codestory.http.errors.BadRequestException;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;

/**
 * This class is responsible for OAuth2 authentication.
 * Note that users object provided here are *not* a user database
 * but a session user cache that should be discarded after the session
 * ends (for example with a Redis key ttl).
 *
 * On each successful login the user is also upserted into the durable
 * {@link UserStore} (user inventory) so that users authenticated by the
 * OAuth provider can be listed and managed. The OAuth provider is the
 * source of truth for the user's name, email and details.
 *
 * If you need to add custom processing for saved user session
 * feel free to override createUser or even processOAuthApiResponse.
 * see {@link #processOAuthApiResponse(Response) processOAuthApiResponse}
 * see {@link #createUser(Map)}
 *
 */
@Singleton
public class OAuth2CookieFilter extends DatashareAuthFilter {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    public static final String REQUEST_CODE_KEY = "code";
    public static final String REQUEST_STATE_KEY = "state";
    static final String STATE_COOKIE_NAME = "_ds_oauth_state";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String STATE_SIGNING_PREFIX = "oauth-state.";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    protected final String oauthDefaultProject;
    private final DefaultApi20 defaultOauthApi;
    private final Integer oauthTtl;
    private final String oauthApiUrl;
    private final String oauthSigninPath;
    private final String oauthCallbackPath;
    private final String oauthAuthorizeUrl;
    private final String oauthTokenUrl;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final byte[] sessionSigningKey;
    private final String oauthScope;
    private final String oauthClaimIdAttribute;
    @Nullable
    private final PostLoginEnroller postLoginEnroller;
    private final UsersIdProviderCache usersCache;
    private final UserStore userStore;

    @Inject
    public OAuth2CookieFilter(PropertiesProvider propertiesProvider, UsersIdProviderCache users, UserStore userStore,
                              SessionIdStore sessionIdStore, @Nullable PostLoginEnroller postLoginEnroller) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), users, sessionIdStore,
              SessionCookies.isSecure(propertiesProvider));
        this.usersCache = users;
        this.userStore = userStore;
        this.oauthAuthorizeUrl = propertiesProvider.get("oauthAuthorizeUrl").orElse("http://localhost");
        this.oauthTokenUrl = propertiesProvider.get("oauthTokenUrl").orElse("http://localhost");
        this.oauthApiUrl = propertiesProvider.get("oauthApiUrl").orElse("http://localhost");
        this.oauthClientId = propertiesProvider.get("oauthClientId").orElse("");
        this.oauthClientSecret = propertiesProvider.get("oauthClientSecret").orElse("");
        this.sessionSigningKey =
                deriveSigningKey(propertiesProvider.get("sessionSigningKey").orElse(this.oauthClientSecret));
        this.oauthCallbackPath = propertiesProvider.get("oauthCallbackPath").orElse("/auth/callback");
        this.oauthSigninPath = propertiesProvider.get("oauthSigninPath").orElse("/auth/signin");
        this.oauthTtl = propertiesProvider.sessionTtlSeconds();
        this.oauthDefaultProject = propertiesProvider.get("oauthDefaultProject").orElse("");
        this.oauthClaimIdAttribute = propertiesProvider.get("oauthClaimIdAttribute").orElse("");
        this.oauthScope = propertiesProvider.get("oauthScope").orElse("");
        this.postLoginEnroller = postLoginEnroller;
        logger.info("created OAuth filter with redirectUrl={} clientId={} callbackPath={} uriPrefix={} loginPath={}",
                    oauthAuthorizeUrl, oauthClientId, oauthCallbackPath, uriPrefix, oauthSigninPath);
        if (this.oauthCallbackPath.startsWith(this.oauthSigninPath)) {
            throw new IllegalStateException(
                    format("oauthCallbackPath (%s) cannot start with oauthSigninPath (%s)", oauthCallbackPath,
                           oauthSigninPath));
        }
        this.defaultOauthApi = new DefaultApi20() {
            @Override
            public String getAccessTokenEndpoint() {
                return oauthTokenUrl;
            }

            @Override
            protected String getAuthorizationBaseUrl() {
                return oauthAuthorizeUrl;
            }
        };
    }

    @Override
    protected Payload authenticationUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (uri.startsWith(oauthSigninPath) && "GET".equals(context.method())) {
            return this.signin(context);
        } else if (uri.startsWith(oauthCallbackPath)) {
            return this.callback(context);
        } else {
            return uri.startsWith("/auth/signout") && "GET".equals(context.method()) ? this.signout(context) :
                   nextFilter.get();
        }
    }

    private Payload callback(Context context) throws IOException, ExecutionException, InterruptedException {
        logger.info("callback called with {}={} {}={}", REQUEST_CODE_KEY, context.get(REQUEST_CODE_KEY),
                    REQUEST_STATE_KEY, context.get(REQUEST_STATE_KEY));
        String state = context.get(REQUEST_STATE_KEY);
        String cookieState = context.cookies().value(STATE_COOKIE_NAME);
        if (context.get(REQUEST_CODE_KEY) == null || state == null || !"GET".equals(context.method()) ||
            !isValidState(state, cookieState, Instant.now())) {
            return Payload.badRequest();
        }
        OAuth20Service service =
                new ServiceBuilder(oauthClientId).apiSecret(oauthClientSecret).callback(getCallbackUrl(context))
                                                 .build(defaultOauthApi);

        logger.info("getting an access token from {} and code value", service);
        OAuth2AccessToken accessToken = service.getAccessToken(context.get(REQUEST_CODE_KEY));

        final OAuthRequest request = new OAuthRequest(Verb.GET, oauthApiUrl);
        service.signRequest(accessToken, request);
        logger.info("sending request to user API signed with the token : {}", request);
        final Response oauthApiResponse = service.execute(request);

        logger.info("received response code from user API : {}", oauthApiResponse.getCode());
        DatashareUser datashareUser = processOAuthApiResponse(oauthApiResponse);
        return Payload.seeOther(this.validRedirectUrl(this.readRedirectUrlInCookie(context)))
                      .withCookie(this.authCookie(this.buildCookie(datashareUser, "/"))).withCookie(stateCookie("", 0));
    }

    protected DatashareUser processOAuthApiResponse(Response oauthApiResponse) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(oauthApiResponse.getBody());
        Map<String, Object> userMap = mapper.convertValue(root, new TypeReference<>() {});
        if (!oauthClaimIdAttribute.isEmpty()) {
            if (userMap.get(oauthClaimIdAttribute) == null) {
                logger.error("The attribute {} does not exist in the response body.", oauthClaimIdAttribute);
                throw new BadRequestException();
            }
            String id = ofNullable(root.get(oauthClaimIdAttribute)).map(JsonNode::asText).orElse(null);
            userMap.put("id", id);
            userMap.put("uid", id);
        }
        DatashareUser datashareUser = createUser(userMap);
        usersCache.saveOrUpdate(datashareUser);
        userStore.save(datashareUser);
        if (postLoginEnroller != null) {
            postLoginEnroller.enroll(datashareUser);
        }
        return datashareUser;
    }

    @NotNull
    protected DatashareUser createUser(Map<String, Object> userMap) {
        return new DatashareUser(userMap);
    }

    @Override
    protected Payload signin(Context context) {
        String state = createState(Instant.now());
        String oauthAuthorizeQS = format("?client_id=%s&redirect_uri=%s&response_type=code&state=%s", oauthClientId,
                                         URLEncoder.encode(getCallbackUrl(context), StandardCharsets.UTF_8), state);
        if (!Objects.equals(oauthScope, "")) {
            oauthAuthorizeQS = oauthAuthorizeQS + "&scope=" + oauthScope;
        }
        int stateTtlSeconds = (int) STATE_TTL.toSeconds();
        return Payload.seeOther(oauthAuthorizeUrl + oauthAuthorizeQS).withCookie(stateCookie(state, stateTtlSeconds));
    }

    private String getCallbackUrl(Context context) {
        String host = ofNullable(context.request().header("x-forwarded-host")).orElse(context.request().header("Host"));
        String proto = SessionCookies.isHttps(context) ? "https" : "http";
        String url = proto + "://" + host + this.oauthCallbackPath;
        logger.info("oauth callback url = {}", url);
        return url;
    }

    /**
     * The state is signed and also sent back in a cookie, so the server keeps nothing between sign-in and callback:
     * anonymous sign-ins cannot fill any store, any server can check the callback, and a state only works in the
     * browser that started the sign-in (no login CSRF). The prefix keeps a signed session id from passing as a state.
     */
    String createState(Instant signinTime) {
        String nonce = SessionCookies.newSessionId();
        long expiry = signinTime.plus(STATE_TTL).getEpochSecond();
        String payload = nonce + "." + expiry;
        String signature = hmacSign(STATE_SIGNING_PREFIX + payload);
        return payload + "." + signature;
    }

    boolean isValidState(String state, String cookieState, Instant now) {
        if (cookieState == null || !constantTimeEquals(state, cookieState)) {
            return false;
        }
        int signatureIndex = state.lastIndexOf('.');
        if (signatureIndex < 0) {
            return false;
        }
        String payload = state.substring(0, signatureIndex);
        String signature = state.substring(signatureIndex + 1);
        if (!constantTimeEquals(hmacSign(STATE_SIGNING_PREFIX + payload), signature)) {
            return false;
        }
        return now.getEpochSecond() < expiryOf(payload);
    }

    private static long expiryOf(String signedPayload) {
        String expiry = signedPayload.substring(signedPayload.lastIndexOf('.') + 1);
        return Long.parseLong(expiry);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private NewCookie stateCookie(String state, int expirySeconds) {
        return new NewCookie(STATE_COOKIE_NAME, state, oauthCallbackPath, true).setExpiry(expirySeconds);
    }

    @Override
    protected String newSessionId(String login) {
        // Sign the session ID with HMAC so it cannot be forged from an OAuth state token.
        // State tokens and session IDs share the session store; signing makes session IDs
        // cryptographically distinct from state tokens, which readSessionIdInCookie rejects.
        String rawId = SessionCookies.newSessionId();
        String signature = hmacSign(rawId);
        String signedSessionId = rawId + "." + signature;
        sessionIdStore.put(signedSessionId, login);
        return signedSessionId;
    }

    @Override
    protected String readSessionIdInCookie(Context context) {
        String sessionId = super.readSessionIdInCookie(context);
        if (sessionId == null) {
            return null;
        }
        // Reject session IDs that are not HMAC-signed (e.g. raw OAuth state tokens)
        int dotIndex = sessionId.indexOf('.');
        if (dotIndex < 0) {
            return null;
        }
        String rawId = sessionId.substring(0, dotIndex);
        String signature = sessionId.substring(dotIndex + 1);
        if (!isValidSignature(rawId, signature)) {
            return null;
        }
        return sessionId;
    }

    // Constant-time comparison so response timing cannot leak the expected signature byte by byte
    private boolean isValidSignature(String rawId, String signature) {
        byte[] expected = hmacSign(rawId).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmacSign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(sessionSigningKey, HMAC_ALGO));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }

    private static byte[] deriveSigningKey(String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec("datashare-session-signing".getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive session signing key", e);
        }
    }

    @Override
    protected Payload signout(Context context) {
        return super.signout(context);
    }

    @Override
    protected int expiry() {
        return oauthTtl;
    }
}
