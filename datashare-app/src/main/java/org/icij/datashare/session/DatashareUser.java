package org.icij.datashare.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.codestory.http.security.Users;
import org.icij.datashare.user.User;

import java.util.HashMap;
import java.util.Map;

public class DatashareUser extends User implements net.codestory.http.security.User {
    public DatashareUser(Map<String, Object> map) { super(map);}
    public DatashareUser(String id) { super(id);}

    @JsonCreator
    public DatashareUser(@JsonProperty("user") User user) {
        super(user);
    }

    @Override public String login() { return id; }
    @Override public String name() { return (String) details.get("name"); }
    @Override public String[] roles() { return isLocal() ? new String[] {LOCAL}: new String[0]; }
    public Object get(String key) { return details.get(key); }
    public static Users singleUser(String name) { return singleUser(User.localUser(name));}
    public static Users singleUser(final User user) {
        return new Users() {
            @Override public net.codestory.http.security.User find(String s, String s1) {
                return s.equals(user.id) ? new DatashareUser(user) : null;
            }
            @Override public net.codestory.http.security.User find(String s) {
                return s.equals(user.id) ? new DatashareUser(user) : null;
            }
        };
    }
    public static Users users(String... logins) {
        return new Users() {
            final Map<String, DatashareUser> users = new HashMap<String, DatashareUser>() {{
                for (String login: logins) {
                    put(login, new DatashareUser(login));
                }
            }};
            @Override public net.codestory.http.security.User find(String s, String s1) {
                return users.get(s);
            }
            @Override public net.codestory.http.security.User find(String s) {
                return users.get(s);
            }
        };
    }
}
