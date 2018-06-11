package org.icij.datashare;

import java.util.Objects;

public class User {
    public final String id;

    public User(final String id) { this.id = id;}

    public String indexName() { return id + "-datashare";}

    public static User local() { return new User("local");}
    @Override public int hashCode() { return Objects.hash(id);}
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }
}
