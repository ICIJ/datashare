package org.icij.datashare.user;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

public class User {
    public final String id;
    public final String name;
    public final String email;
    public final String provider;

    public User(final String id, String name, String email, String provider) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.provider = provider;
    }

    public User(final String id, String name, String email) {
        this(id, name, email, "local");
    }

    public User(final String id) {
        this(id, null, null, "local");
    }

    public String queueName() { return "extract:queue_" + id;}
    @JsonIgnore
    public String getPath() { return this.equals(local()) || isNull()? "": id;}
    @JsonIgnore
    public boolean isNull() { return this.id == null;}

    public static User local() { return new User("local");}
    public static User nullUser() { return new User(null);}
    @Override public int hashCode() { return Objects.hash(id);}
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof User)) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }
}
