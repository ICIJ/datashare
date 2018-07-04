package org.icij.datashare.session;

import net.codestory.http.convert.TypeConvert;
import net.codestory.http.security.Users;
import org.icij.datashare.user.User;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static net.codestory.http.convert.TypeConvert.toJson;

public class OAuth2User extends User implements net.codestory.http.security.User {
    final Map<String, String> userMap;

    public OAuth2User(final Map<String, String> userMap) {
        super(userMap.get("uid"));
        this.userMap = userMap;
    }

    OAuth2User(final String login) {
        super(login);
        this.userMap = new HashMap<>();
    }

    public static OAuth2User fromJson(String json) {
        HashMap hashMap = TypeConvert.fromJson(json, HashMap.class);
        return new OAuth2User(convert(hashMap));
    }

    private static Map<String, String> convert(final HashMap hashMap) {
        Set<Map.Entry<Object, Object>> set = hashMap.entrySet();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry e: set) {
            if (e.getValue() != null) {
                if (e.getValue() instanceof String) {
                    result.put((String) e.getKey(), (String) e.getValue());
                } else {
                    result.put((String) e.getKey(), toJson(e.getValue()));
                }
            }
        }
        return result;
    }

    @Override public String login() { return userMap.get("uid");}
    @Override public String name() { return userMap.get("name");}
    @Override public String[] roles() { return new String[0];}
    public static OAuth2User local() {
        return new OAuth2User(new HashMap<String, String>() {{ put("uid", "local");}});
    }

    public static Users singleUser(String name) {
        return new Users() {
            OAuth2User user = new OAuth2User(name);
            @Override public net.codestory.http.security.User find(String s, String s1) { return user;}
            @Override public net.codestory.http.security.User find(String s) { return user;}
        };
    }
    public static Users users(String... logins) {
        return new Users() {
            Map<String, OAuth2User> users = new HashMap<String, OAuth2User>() {{
                for (String login: logins) {
                    put(login, new OAuth2User(login));
                }
            }};
            @Override public net.codestory.http.security.User find(String s, String s1) { return users.get(s);}
            @Override public net.codestory.http.security.User find(String s) { return users.get(s);}
        };
    }
}
