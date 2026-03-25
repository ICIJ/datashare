package org.icij.datashare.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;

import static java.util.Optional.ofNullable;

@Singleton
public class FormAuthFilter extends DatashareAuthFilter {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    static final String LOGIN_PATH = "/auth/login";
    static final String SIGNOUT_PATH = "/auth/signout";

    private final int sessionTtl;
    @Nullable
    private final PostLoginEnroller postLoginEnroller;

    @Inject
    public FormAuthFilter(PropertiesProvider propertiesProvider, UsersWritable users,
                          SessionIdStore sessionIdStore, @Nullable PostLoginEnroller postLoginEnroller) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), users, sessionIdStore);
        this.sessionTtl = Integer.parseInt(ofNullable(propertiesProvider.getProperties().getProperty("sessionTtlSeconds")).orElse("600"));
        this.postLoginEnroller = postLoginEnroller;
        logger.info("created FormAuthFilter with uriPrefix={}", uriPrefix);
    }

    @Override
    protected Payload authenticationUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (uri.startsWith(LOGIN_PATH) && "POST".equals(context.method())) {
            return login(context);
        } else if (uri.startsWith(SIGNOUT_PATH) && "GET".equals(context.method())) {
            return signout(context);
        }
        return nextFilter.get();
    }

    private Payload login(Context context) {
        JsonNode body;
        try {
            body = new ObjectMapper().readTree(context.request().content());
        } catch (IOException e) {
            return new Payload(401);
        }
        String username = ofNullable(body.get("username")).map(JsonNode::asText).orElse(null);
        String password = ofNullable(body.get("password")).map(JsonNode::asText).orElse(null);
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return new Payload(401);
        }
        User user = users.find(username, password);
        if (user == null) {
            logger.warn("failed login attempt for user {}", username);
            return new Payload(401);
        }
        logger.debug("user {} logged in successfully", username);
        if (postLoginEnroller != null) {
            postLoginEnroller.enroll((DatashareUser) user);
        }
        return new Payload(200)
                .withCookie(this.authCookie(this.buildCookie(user, "/")))
                .withCookie(CsrfFilter.csrfCookie(CsrfFilter.generateToken()));
    }

    @Override protected int expiry() { return sessionTtl; }
}
