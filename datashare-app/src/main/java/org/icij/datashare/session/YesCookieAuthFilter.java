package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;

import java.util.HashMap;

import static java.util.Collections.singletonList;

public class YesCookieAuthFilter extends CookieAuthFilter {
    private final Integer ttl;
    private final String project;

    @Inject
    public YesCookieAuthFilter(PropertiesProvider propertiesProvider) {
        super(propertiesProvider.get("protectedUrPrefix").orElse("/"), new UsersInRedis(propertiesProvider), new RedisSessionIdStore(propertiesProvider));
        this.ttl = Integer.valueOf(propertiesProvider.get("sessionTtlSeconds").orElse("1"));
        this.project = propertiesProvider.get("defaultProject").orElse("local-datashare");
    }

    @Override
    protected Payload otherUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        Payload payload = super.otherUri(uri, context, nextFilter);
        if (payload.code() == 401) {
            User user = createUser(NameGenerator.generate());
            context.setCurrentUser(user);
            return nextFilter.get().withCookie(this.authCookie(this.buildCookie(user, "/")));
        }
        return payload;
    }

    private User createUser(String userName) {
        DatashareUser user = new DatashareUser(new HashMap<String, Object>() {{
            put("uid", userName);
            put(DatashareUser.XEMX_APPLICATIONS_KEY, new HashMap<String, Object>() {{
                put(DatashareUser.XEMX_DATASHARE_KEY, singletonList(project));
            }});
        }});
        ((UsersInRedis)users).saveOrUpdate(user);
        return user;
    }

    @Override protected String cookieName() { return "_ds_session_id";}
    @Override protected int expiry() { return ttl;}
    @Override protected boolean redirectToLogin(String uri) { return false;}
}
