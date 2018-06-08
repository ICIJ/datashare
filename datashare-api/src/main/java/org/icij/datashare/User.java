package org.icij.datashare;

public class User {
    public final String id;

    public User(final String id) { this.id = id;}
    public String indexName() { return id + "-datashare";}
    public static User local() { return new User("local");}
}
