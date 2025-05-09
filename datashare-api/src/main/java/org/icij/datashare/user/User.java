package org.icij.datashare.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.Entity;
import org.icij.datashare.json.JsonUtils;
import org.icij.datashare.text.Project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.json.JsonUtils.deserialize;
import static org.icij.datashare.text.StringUtils.isEmpty;

public class User implements Entity, Comparable<User> {
    public static final String LOCAL = "local";
    public static final String DEFAULT_PROJECTS_KEY = "groups_by_applications.datashare";
    public static final String JVM_PROJECT_KEY = "datashare.user.projects";

    public final String id;
    public final String name;
    public final String email;
    public final String provider;
    public final Map<String, Object> details;
    private final HashSet<Project> projects = new HashSet<>();
    @JsonIgnore
    private final String jsonProjectKey;

    public User(final String id, String name, String email, String provider, String jsonDetails) {
        this(id, name, email, provider, deserialize(jsonDetails));
    }

    @JsonCreator
    public User(@JsonProperty("id") final String id, @JsonProperty("name") String name,
                @JsonProperty("email") String email, @JsonProperty("provider") String provider,
                @JsonProperty("details") Map<String, Object> details) {
        this(id, name, email, provider, details, getDefaultProjectsKey());
    }

    public User(String id, String name,
                String email, String provider,
                Map<String, Object> details, String jsonProjectKey) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.provider = provider;
        this.details = unmodifiableMap(ofNullable(details).orElse(new HashMap<>()));
        this.jsonProjectKey = jsonProjectKey;
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
    public List<String> getApplicationProjectNames() {
        List<String> jsonKeys = List.of(jsonProjectKey.split("\\."));
        Map<String, Object> node = details;
        for (String key: jsonKeys)  {
            Object o = node.get(key);
            if (o instanceof Map<?,?>) {
                node = (Map<String, Object>) o;
            } else if (jsonKeys.indexOf(key) == jsonKeys.size() -1 && o instanceof List<?>) {
                return (List<String>) o;
            } else {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    @JsonIgnore
    public List<Project> getApplicationProjects() {
        return getApplicationProjectNames().stream().map(Project::new).collect(Collectors.toList());
    }

    @JsonIgnore
    public List<String> getProjectNames() {
        return getProjects().stream().map(p -> p.name).collect(Collectors.toList());
    }

    @JsonIgnore
    public List<Project> getProjects() {
        HashSet<Project> uniqueProjects = new HashSet<>(projects);
        uniqueProjects.addAll(getApplicationProjects());
        return new ArrayList<>(uniqueProjects);
    }

    @JsonIgnore
    public User addProject(String newProjectName) {
        Project newProject = new Project(newProjectName);
        return addProject(newProject);
    }

    @JsonIgnore
    public User addProject(Project newProject) {
        if (!getProjects().contains(newProject)) {
            projects.add(newProject);
        }
        return this;
    }

    @JsonIgnore
    public User addProjectNames(List<String> newProjectNames) {
        // We add each project one by one to ensure that even if `newProjects` contains
        // duplicates, they are only added once
        newProjectNames.forEach(this::addProject);
        return this;
    }

    @JsonIgnore
    public User addProjects(List<Project> newProjects) {
        // We add each project one by one to ensure that even if `newProjects` contains
        // duplicates, they are only added once
        newProjects.forEach(this::addProject);
        return this;
    }

    @JsonIgnore
    public User setProjectNames(List<String> newProjectNames) {
        return clearProjects().addProjectNames(newProjectNames);
    }

    @JsonIgnore
    public User setProjects(List<Project> newProjects) {
        return clearProjects().addProjects(newProjects);
    }

    @JsonIgnore
    public User clearProjects() {
        projects.clear();
        return this;
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

    public boolean isGranted(String projectName) {
        return getProjectNames().contains(projectName);
    }

    public boolean isGranted(Project project) {
        return getProjects().contains(project);
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
    public static User localUser(String id) {
        return localUser(id, singletonList(id + "-datashare"));
    }

    public static User localUser(String id, List<String> projectNames) {
        String[] keys = DEFAULT_PROJECTS_KEY.split("\\.");
        return new User(
                Map.of("uid", id, keys[0], Map.of(keys[1], projectNames))
        );
    }
    public static User nullUser() { return new User((String)null);}
    @Override
    public int hashCode() { return Objects.hash(id);}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return Objects.equals(id, user.id);
    }

    static String getDefaultProjectsKey() {
        return isEmpty(System.getProperty(JVM_PROJECT_KEY)) ? DEFAULT_PROJECTS_KEY: System.getProperty(JVM_PROJECT_KEY);
    }

    @Override
    public int compareTo(User user) {
        return id.compareTo(user.id);
    }
}
