package org.icij.datashare.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.codestory.http.security.Users;
import org.icij.datashare.user.User;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static org.apache.commons.collections4.map.UnmodifiableMap.unmodifiableMap;

public class HashMapUser extends User implements net.codestory.http.security.User {
    private static final String DATASHARE_INDICES_KEY = "datashare_indices";
    final Map<String, Object> userMap;

    public HashMapUser(final Map<String, Object> userMap) {
        super((String) userMap.get("uid"));
        this.userMap = unmodifiableMap(userMap);
    }

    HashMapUser(final String login) {
        this(new HashMap<String, Object>() {{ put("uid", login); }});
    }

    static HashMapUser fromJson(String json) {
        HashMap<String, Object> hashMap = null;
        try {
            hashMap = new ObjectMapper().readValue(json, new TypeReference<HashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return new HashMapUser(hashMap);
    }

    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(this.userMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getProjects() {
        return (List<String>) ofNullable(userMap.get(DATASHARE_INDICES_KEY)).orElse(new LinkedList<>());
    }

    public Map<String, Object> getMap() {
        return userMap.entrySet().stream().filter(
                k -> !k.getKey().equalsIgnoreCase("password")).
                collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override public String login() { return id; }
    @Override public String name() { return (String) userMap.get("name"); }
    @Override public String[] roles() { return new String[0]; }
    public Object get(String key) { return userMap.get(key); }
    public static HashMapUser local() { return localUser("local"); }

    public static HashMapUser localUser(String id) {
        return new HashMapUser(new HashMap<String, Object>() {{ put("uid", id); put(DATASHARE_INDICES_KEY, singletonList(id + "-datashare"));}}) {
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
        return getProjects().contains(index);
    }
}
