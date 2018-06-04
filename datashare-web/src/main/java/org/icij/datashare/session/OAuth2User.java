package org.icij.datashare.session;

import net.codestory.http.security.User;

import java.util.Map;

public class OAuth2User implements User {
    final Map<String, String> userMap;

    public OAuth2User(final Map<String, String> userMap) {
        this.userMap = userMap;
    }

    @Override
    public String login() {
        return userMap.get("login");
    }

    @Override
    public String[] roles() {
        return new String[0];
    }
}
