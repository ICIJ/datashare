package org.icij.datashare.web;

import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.session.HashMapUser;

import java.util.Map;

@Prefix("/api/users")
public class UserResource {
    /**
     * gets the user's session information
     *
     * @return 200 and the user map
     *
     * Example :
     * $(curl -i localhost:8080/api/users/me)
     */
    @Get("/me")
    public Map<String, Object> getUser(Context context) {
        return ((HashMapUser) context.currentUser()).getMap();
    }
}
