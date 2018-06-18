package org.icij.datashare.session;

import org.icij.datashare.user.User;

import java.util.Map;

public class OAuth2User extends User implements net.codestory.http.security.User {
    final Map<String, String> userMap;

    public OAuth2User(final Map<String, String> userMap) {
        super(userMap.get("uid"));
        this.userMap = userMap;
    }

    @Override public String login() { return userMap.get("uid");}
    @Override public String name() { return userMap.get("name");}
    @Override public String[] roles() { return new String[0];}
}
