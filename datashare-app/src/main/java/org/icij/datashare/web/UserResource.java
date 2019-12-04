package org.icij.datashare.web;

import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.session.HashMapUser;

import java.util.Map;

@Prefix("/api/user")
public class UserResource {
    /**
     * gets the user's session information
     *
     * @return 200 and the user map
     *
     * Example :
     * $(curl -i localhost:8080/api/user)
     */
    @Get()
    public Map<String, String> getUser(Context context) {
        return ((HashMapUser) context.currentUser()).getMap();
    }
}
