package org.icij.datashare.session;

import net.codestory.http.security.User;

import java.util.HashMap;

public class UserSession extends HashMap<String, String> implements User {
    @Override
    public String login() {
        return null;
    }

    @Override
    public String[] roles() {
        return new String[0];
    }
}
