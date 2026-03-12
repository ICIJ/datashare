package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;

import javax.annotation.Nullable;
import java.util.HashMap;

import static java.util.Collections.singletonList;

public class YesBasicAuthFilter extends BasicAuthFilter {
    @Nullable
    private final PostLoginEnroller postLoginEnroller;

    @Inject
    public YesBasicAuthFilter(final PropertiesProvider propertiesProvider, @Nullable PostLoginEnroller postLoginEnroller) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), "datashare", new DummyUsers());
        this.postLoginEnroller = postLoginEnroller;
    }

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        Payload payload = super.apply(uri, context, nextFilter);
        if (postLoginEnroller != null && context.currentUser() instanceof DatashareUser user) {
            postLoginEnroller.enroll(user);
        }
        return payload;
    }

    static class DummyUsers implements Users {
        @Override public User find(String login, String password) { return find(login);}
        @Override public User find(String login) { return new DatashareUser(new HashMap<>() {{
            put("uid", login);
            put("groups_by_applications", new HashMap<>() {{
                put("datashare", singletonList("local-datashare"));
            }});
        }});}
    }
}
