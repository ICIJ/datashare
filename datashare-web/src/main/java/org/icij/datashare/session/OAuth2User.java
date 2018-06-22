package org.icij.datashare.session;

import net.codestory.http.convert.TypeConvert;
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
}
