package org.icij.datashare.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.icij.datashare.json.JsonUtils;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.json.JsonUtils.deserialize;

public class User {
    public static final String LOCAL = "local";
    public static final String DATASHARE_PROJECTS_KEY = "datashare_projects";
    public final String id;
    public final String name;
    public final String email;
    public final String provider;
    public final Map<String, Object> details;

    public User(final String id, String name, String email, String provider, String jsonDetails) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.provider = provider;
        this.details = unmodifiableMap(deserialize(jsonDetails));
    }

    public User(final String id, String name, String email, String provider, Map<String, Object> details) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.provider = provider;
        this.details = unmodifiableMap(details);
    }

    public User(final String id, String name, String email, String provider) {
        this(id, name, email, provider, new HashMap<>());
    }

    public User(final String id, String name, String email) {
        this(id, name, email, LOCAL);
    }

    public User(final String id) {
        this(id, null, null, LOCAL);
    }

    public User(Map<String, Object> map) {
        this((String)map.get("uid"), (String)map.get("name"), (String)map.get("email"), (String)map.getOrDefault("provider", LOCAL), map);
    }

    public static User fromJson(String json, String provider) {
        if (json == null) return null;
        Map<String, Object> hashMap = deserialize(json);
        hashMap.put("provider", provider);
        return new User(hashMap);
    }

    public List<String> getProjects() {
        return (List<String>) ofNullable(details.get(DATASHARE_PROJECTS_KEY)).orElse(new LinkedList<>());
    }

    public Map<String, Object> getDetails() {
        return details.entrySet().stream().
                filter(k -> k.getValue() != null).
                filter(k -> !k.getKey().equalsIgnoreCase("password")).
                collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public String getJsonDetails() {
        return JsonUtils.serialize(getDetails());
    }

    public boolean isGranted(String index) {
            return getProjects().contains(index);
        }

    public String queueName() { return "extract:queue_" + id;}
    @JsonIgnore
    public String getPath() { return this.equals(local()) || isNull() ? "" : id;}
    @JsonIgnore
    public boolean isNull() { return this.id == null;}
    @JsonIgnore
    public boolean isLocal() { return LOCAL.equals(this.id);}
    public static User local() { return localUser(LOCAL);}
    public static User localUser(String id) { return new User(new HashMap<String, Object>() {{ put("uid", id); put(DATASHARE_PROJECTS_KEY, singletonList(id + "-datashare"));}});}
    public static User nullUser() { return new User((String)null);}
    @Override
    public int hashCode() { return Objects.hash(id);}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof User)) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }
}
