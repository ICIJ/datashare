package org.icij.datashare.session;

import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;

public class OAuth2CookieAuthFilter extends CookieAuthFilter {
    public OAuth2CookieAuthFilter(String uriPrefix, Users users, SessionIdStore sessionIdStore) {
        super(uriPrefix, users, sessionIdStore);
    }
}
