package org.icij.datashare.session;

import net.codestory.http.convert.TypeConvert;
import net.codestory.http.security.Users;
import org.icij.datashare.user.User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Optional.ofNullable;
import static net.codestory.http.convert.TypeConvert.toJson;
import static org.apache.commons.collections4.map.UnmodifiableMap.unmodifiableMap;

public class HashMapUser extends User implements net.codestory.http.security.User {
    private static final String DATASHARE_INDICES_KEY = "datashare_indices";
    final Map<String, String> userMap;

    public HashMapUser(final Map<String, String> userMap) {
        super(userMap.get("uid"));
        this.userMap = unmodifiableMap(userMap);
    }

    HashMapUser(final String login) {
        this(new HashMap<String, String>() {{ put("uid", login); }});
    }

    static HashMapUser fromJson(String json) {
        HashMap hashMap = TypeConvert.fromJson(json, HashMap.class);
        return new HashMapUser(convert(hashMap));
    }

    public List<String> getProjects() {
        return TypeConvert.fromJson(ofNullable(userMap.get(DATASHARE_INDICES_KEY)).orElse("[]"), List.class);
    }

    public Map<String, String> getMap() {
        return userMap;
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

    @Override public String login() { return id;}
    @Override public String name() { return userMap.get("name");}
    @Override public String[] roles() { return new String[0];}
    public String get(String key) {return userMap.get(key);}
    public static HashMapUser local() { return localUser("local"); }

    public static HashMapUser localUser(String id) {
        return new HashMapUser(id) {
            @Override public String[] roles() { return new String[] {"local"};}
        };
    }

    public static Users singleUser(String name) { return singleUser(localUser(name));}
    public static Users singleUser(final HashMapUser user) {
        return new Users() {
            @Override public net.codestory.http.security.User find(String s, String s1) { return s.equals(user.id) ? user : null;}
            @Override public net.codestory.http.security.User find(String s) { return s.equals(user.id) ? user : null;}
        };
    }
    public static Users users(String... logins) {
        return new Users() {
            Map<String, HashMapUser> users = new HashMap<String, HashMapUser>() {{
                for (String login: logins) {
                    put(login, new HashMapUser(login));
                }
            }};
            @Override public net.codestory.http.security.User find(String s, String s1) { return users.get(s);}
            @Override public net.codestory.http.security.User find(String s) { return users.get(s);}
        };
    }

    public boolean isGranted(String index) {
        return getProjects().contains(index) || defaultProject().equals(index);
    }
}
