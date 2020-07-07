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

public class DatashareUser extends User implements net.codestory.http.security.User {
    public static final String DATASHARE_PROJECTS_KEY = "datashare_projects";
    final Map<String, Object> details;

    public DatashareUser(final Map<String, Object> details) {
        super((String) details.get("uid"));
        this.details = unmodifiableMap(details);
    }

    DatashareUser(final String login) {
        this(new HashMap<String, Object>() {{ put("uid", login); }});
    }

    static DatashareUser fromJson(String json) {
        if (json == null) return null;
        HashMap<String, Object> hashMap;
        try {
            hashMap = new ObjectMapper().readValue(json, new TypeReference<HashMap<String, Object>>() {});
            return new DatashareUser(hashMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(this.details);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getProjects() {
        return (List<String>) ofNullable(details.get(DATASHARE_PROJECTS_KEY)).orElse(new LinkedList<>());
    }

    public Map<String, Object> getMap() {
        return details.entrySet().stream().
                filter(k -> k.getValue() != null).
                filter(k -> !k.getKey().equalsIgnoreCase("password")).
                collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override public String login() { return id; }
    @Override public String name() { return (String) details.get("name"); }
    @Override public String[] roles() { return new String[0]; }
    public Object get(String key) { return details.get(key); }
    public static DatashareUser local() { return localUser("local"); }

    public static DatashareUser localUser(String id) {
        return new DatashareUser(new HashMap<String, Object>() {{ put("uid", id); put(DATASHARE_PROJECTS_KEY, singletonList(id + "-datashare"));}}) {
            @Override public String[] roles() { return new String[] {"local"};}
        };
    }

    public static Users singleUser(String name) { return singleUser(localUser(name));}
    public static Users singleUser(final DatashareUser user) {
        return new Users() {
            @Override public net.codestory.http.security.User find(String s, String s1) { return s.equals(user.id) ? user : null;}
            @Override public net.codestory.http.security.User find(String s) { return s.equals(user.id) ? user : null;}
        };
    }
    public static Users users(String... logins) {
        return new Users() {
            Map<String, DatashareUser> users = new HashMap<String, DatashareUser>() {{
                for (String login: logins) {
                    put(login, new DatashareUser(login));
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
