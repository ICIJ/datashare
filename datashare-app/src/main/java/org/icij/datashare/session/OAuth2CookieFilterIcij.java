package org.icij.datashare.session;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

import static java.util.Collections.singletonList;

@Singleton
public class OAuth2CookieFilterIcij extends OAuth2CookieFilter {
    @Inject
    public OAuth2CookieFilterIcij(PropertiesProvider propertiesProvider, UsersWritable users, SessionIdStore sessionIdStore) {
        super(propertiesProvider, users, sessionIdStore);
    }

    @NotNull
    @Override
    protected DatashareUser createUser(Map<String, Object> userMap) {
        if (!oauthDefaultProject.isEmpty()) {
            userMap.put("provider", "icij");
            userMap.put("groups_by_applications", Map.of("datashare", singletonList(oauthDefaultProject)));
        }
        return new DatashareUser(userMap);
    }
}
