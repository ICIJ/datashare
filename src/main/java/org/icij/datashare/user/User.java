package org.icij.datashare.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.Entity;
import org.icij.datashare.json.JsonUtils;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.json.JsonUtils.deserialize;

public class User implements Entity {
    public static final String LOCAL = "local";
    public static final String XEMX_APPLICATIONS_KEY = "groups_by_applications";
    public static final String XEMX_DATASHARE_KEY = "datashare";
    public final String id;
    public final String name;
    public final String email;
    public final String provider;
    public final Map<String, Object> details;
    private final List<String> projects = new ArrayList<>();

    public User(final String id, String name, String email, String provider, String jsonDetails) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.provider = provider;
        this.details = unmodifiableMap(deserialize(jsonDetails));
    }

    @JsonCreator
    public User(@JsonProperty("id") final String id, @JsonProperty("name") String name,
                @JsonProperty("email") String email, @JsonProperty("provider") String provider,
                @JsonProperty("details") Map<String, Object> details) {
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

    public User(User user) {
        this(ofNullable(user).orElse(nullUser()).id,
                ofNullable(user).orElse(nullUser()).name,
                ofNullable(user).orElse(nullUser()).email,
                ofNullable(user).orElse(nullUser()).provider,
                ofNullable(user).orElse(nullUser()).details);
    }

    public static User fromJson(String json, String provider) {
        if (json == null) return null;
        Map<String, Object> hashMap = deserialize(json);
        hashMap.put("provider", provider);
        return new User(hashMap);
    }

    @JsonIgnore
    public List<String> getApplicationProjects() {
        HashMap<String, Object> applications = (HashMap<String, Object>) ofNullable(details.get(XEMX_APPLICATIONS_KEY)).orElse(new HashMap<>());
        return (List<String>) ofNullable(applications.get(XEMX_DATASHARE_KEY)).orElse(new LinkedList<>());
    }

    @JsonIgnore
    public List<String> getProjects() {
        HashSet<String> uniqueProjects = new HashSet<>();

        uniqueProjects.addAll(projects);
        uniqueProjects.addAll(getApplicationProjects());

        return new ArrayList<>(uniqueProjects);
    }

    @JsonIgnore
    public void addProject(String newProject) {
        if (!getProjects().contains(newProject)) {
            projects.add(newProject);
        }
    }

    @JsonIgnore
    public void addProjects(List<String> newProjects) {
        // We add each project one by one to ensure that even if `newProjects` contains
        // duplicates, they are only added once
        newProjects.forEach(this::addProject);
    }

    @JsonIgnore
    public void setProjects(List<String> newProjects) {
        projects.clear();
        addProjects(newProjects);
    }

    @JsonIgnore
    public Map<String, Object> getDetails() {
        return details.entrySet().stream().
                filter(k -> k.getValue() != null).
                filter(k -> !k.getKey().equalsIgnoreCase("password")).
                collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @JsonIgnore
    public String getJsonDetails() {
        return JsonUtils.serialize(getDetails());
    }

    public boolean isGranted(String index) {
            return getProjects().contains(index);
        }

    @Override public String getId() { return id;}
    public String queueName() { return "extract:queue_" + id;}
    @JsonIgnore
    public String getPath() { return this.equals(local()) || isNull() ? "" : id;}
    @JsonIgnore
    public boolean isNull() { return this.id == null;}
    @JsonIgnore
    public boolean isLocal() { return LOCAL.equals(this.id);}
    public static User local() { return localUser(LOCAL);}
    public static User localUser(String id) { return new User(new HashMap<String, Object>() {{ put("uid", id); put(XEMX_APPLICATIONS_KEY, new HashMap<String, Object>() {{ put(XEMX_DATASHARE_KEY, singletonList(id + "-datashare"));}});}});}
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
