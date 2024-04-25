package org.icij.datashare.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static java.util.Optional.ofNullable;

@Singleton
public class ALBCognitoOAuth2CookieFilter extends OAuth2CookieFilter {
    private final DefaultApi20 defaultOauthApi;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Integer oauthTtl;
    private final String oauthApiUrl;
    private final String oauthAuthorizeUrl;
    private final String oauthCallbackPath;
    private final String oauthTokenUrl;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final String oauthDefaultProject;
    private final String oauthClaimIdAttribute;
    @Inject
    public ALBCognitoOAuth2CookieFilter(PropertiesProvider propertiesProvider, UsersWritable users, SessionIdStore sessionIdStore) {
        super(propertiesProvider, users, sessionIdStore);
        this.oauthTtl = Integer.valueOf(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("600"));
        this.oauthAuthorizeUrl = propertiesProvider.get("oauthAuthorizeUrl").orElse("http://localhost");
        this.oauthTokenUrl = propertiesProvider.get("oauthTokenUrl").orElse("http://localhost");
        this.oauthApiUrl = propertiesProvider.get("oauthApiUrl").orElse("http://localhost");
        this.oauthClientId = propertiesProvider.get("oauthClientId").orElse("");
        this.oauthClientSecret = propertiesProvider.get("oauthClientSecret").orElse("");
        this.oauthDefaultProject = propertiesProvider.get("oauthDefaultProject").orElse("");
        this.oauthCallbackPath = propertiesProvider.get("oauthCallbackPath").orElse("/auth/callback");

        // The attribute to be used as the user ID. Since this is coming from Cognito, which will
        // always have the username claim, we can safely default to "username".
        // This can be overridden by setting the "oauthClaimIdAttribute" property when bootstrapping Datashare.
        this.oauthClaimIdAttribute = propertiesProvider.get("oauthClaimIdAttribute").orElse("username");
        this.defaultOauthApi = new DefaultApi20() {
            @Override public String getAccessTokenEndpoint() { return oauthTokenUrl;}
            @Override protected String getAuthorizationBaseUrl() { return oauthAuthorizeUrl;}
        };

        logger.info("Using ALBCognitoOAuth2CookieFilter with oauthAuthorizeUrl={}, oauthTokenUrl={}, oauthApiUrl={}, oauthCallbackPath={}, oauthClaimIdAttribute={}",
                oauthAuthorizeUrl, oauthTokenUrl, oauthApiUrl, oauthCallbackPath, oauthClaimIdAttribute);
    }

    @Override
    protected Payload callback(Context context) throws IOException, ExecutionException, InterruptedException {
        logger.info("callback: called with {}={} {}={}", REQUEST_CODE_KEY, context.get(REQUEST_CODE_KEY), REQUEST_STATE_KEY, context.get(REQUEST_STATE_KEY));
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

        logger.info("Received response body from user API: {}", oauthApiResponse.getBody());
        return processOAuthApiResponse(oauthApiResponse.getBody(), context);
    }

    /**
     * Processes the OAuth API response that comes from ALB + Cognito,
     * amends it with user ID and 'groups_by_applications',
     * creates a DatashareUser, and updates the user data store.
     *
     * @param oauthApiResponseBody The JSON body of the OAuth API response.
     * @param context The web context.
     * @return A payload indicating the outcome of the operation.
     */
    protected Payload processOAuthApiResponse(String oauthApiResponseBody, Context context) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = (ObjectNode) mapper.readTree(oauthApiResponseBody);

            // Amend root with user ID
            if (root.has(oauthClaimIdAttribute)) {
                // Put into root the user ID as the 'id' attribute.
                root.put("id", root.get(oauthClaimIdAttribute).asText());

                // Put into root the user ID as the 'uid' attribute as well.
                // This is to ensure that the user ID is always available in the user details.
                // See org.icij.datashare.user.User class for more details.
                root.put("uid", root.get(oauthClaimIdAttribute).asText());
                logger.info("Modified user with 'id': {}", root.get(oauthClaimIdAttribute).asText());
            } else {
                logger.error("The attribute {} does not exist in the response body.", oauthClaimIdAttribute);
                return Payload.badRequest();
            }

            // Set 'groups_by_applications'
            if (!oauthDefaultProject.isEmpty()) {
                ArrayNode arrayNode = mapper.createArrayNode();
                arrayNode.add(oauthDefaultProject);
                ObjectNode objectNode = mapper.createObjectNode();
                objectNode.set("datashare", arrayNode);
                root.put("groups_by_applications", objectNode);
                logger.info("Modified user with 'groups_by_applications': {}", root);
            }

            // Create DatashareUser and update user data store
            User user = User.fromJson(mapper.writeValueAsString(root), "icij");
            DatashareUser datashareUser = new DatashareUser(user.details);
            writableUsers().saveOrUpdate(datashareUser);

            return Payload.seeOther(validRedirectUrl(readRedirectUrlInCookie(context)))
                    .withCookie(authCookie(buildCookie(datashareUser, "/")));
        } catch (Exception e) {
            logger.error("Error processing OAuth API response: ", e);
            return Payload.badRequest();
        }
    }

    private String getCallbackUrl(Context context) {
        String host = ofNullable(context.request().header("x-forwarded-host")).orElse(context.request().header("Host"));
        String proto = ofNullable(context.request().header("x-forwarded-proto")).orElse(context.request().isSecure() ? "https" : "http");
        String url = proto + "://" + host + this.oauthCallbackPath;
        logger.info("oauth callback url = {}", url);
        return url;
    }
    @Override protected Payload signout(Context context) {
        return super.signout(context);
    }
    @Override protected String cookieName() { return "_ds_session_id";}
    @Override protected int expiry() { return oauthTtl; }
    @Override protected boolean redirectToLogin(String uri) { return false; }
    private UsersWritable writableUsers() { return (UsersWritable) users; }
}
