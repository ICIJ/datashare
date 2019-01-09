package org.icij.datashare.session;

import net.codestory.http.convert.TypeConvert;
import net.codestory.http.security.Users;
import org.icij.datashare.user.User;

import java.util.*;

import static java.util.Arrays.asList;
import static net.codestory.http.convert.TypeConvert.toJson;

public class HashMapUser extends User implements net.codestory.http.security.User {
    final Map<String, String> userMap;

    public HashMapUser(final Map<String, String> userMap) {
        super(userMap.get("uid"));
        this.userMap = userMap;
    }

    HashMapUser(final String login) {
        this(new HashMap<String, String>() {{ put("uid", login); }});
    }

    public static HashMapUser fromJson(String json) {
        HashMap hashMap = TypeConvert.fromJson(json, HashMap.class);
        return new HashMapUser(convert(hashMap));
    }

    public List<String> getIndices() {
        return this.equals(local())? new ArrayList<>() : asList("luxleaks", "offshoreleaks");
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
    public static HashMapUser local() {
        return new HashMapUser("local") {
            @Override public String[] roles() { return new String[] {"local"};}
        };
    }

    public static Users singleUser(String name) { return singleUser(new HashMapUser(name));}
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
}
