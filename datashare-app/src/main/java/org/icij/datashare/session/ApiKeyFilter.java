package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.codestory.http.constants.Headers.CACHE_CONTROL;
import static net.codestory.http.constants.HttpStatus.UNAUTHORIZED;

@Singleton
public class ApiKeyFilter implements Filter {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Users users;
    private final ApiKeyStore apiKeyStore;
    private final String protectedUrlPrefix;

    @Inject
    public ApiKeyFilter(UsersWritable users, ApiKeyStore apiKeyStore) {
        this.users = users;
        this.apiKeyStore = apiKeyStore;
        protectedUrlPrefix = "/api";
        logger.info("api filter activated for url {} with store {}", protectedUrlPrefix, apiKeyStore.getClass());
    }

    @Override
    public boolean matches(String uri, Context context) { return uri.startsWith(protectedUrlPrefix);}

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (context.cookies().get(dsCookieName()) != null) {
            return nextFilter.get();
        }
        String apiKey = readApiKeyInHeader(context);
        if (apiKey != null) {
          String login = apiKeyStore.getLogin(apiKey);
          if (login != null) {
            User user = users.find(login);
            context.setCurrentUser(user);
            return nextFilter.get().withHeader(CACHE_CONTROL, "must-revalidate");
          }
        }
        return new Payload(UNAUTHORIZED);
    }

    protected String readApiKeyInHeader(Context context) { return getToken(context.header("authorization"));}

    private String getToken(String authorizationHeader) {
        if (authorizationHeader == null) return null;
        String[] typeAndCredential = authorizationHeader.split("\\s");
        if (typeAndCredential.length != 2) return null;
        return typeAndCredential[0].equalsIgnoreCase("bearer") ? typeAndCredential[1] : null;
    }

    protected String dsCookieName() { return "_ds_session_id";}
}
