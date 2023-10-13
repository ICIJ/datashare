package org.icij.datashare;

import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;

import java.net.URI;
import java.util.Date;
import java.util.Objects;

public class UserEvent {
    public enum Type {DOCUMENT((short) 0), SEARCH((short)1);
        public final short id;
        Type(short id) {
            this.id = id;
        }

        public static Type fromId(final int id) {
            for (Type t: Type.values()) {
                if (t.id == id) {
                    return t;
                }
            }
            throw new IllegalArgumentException("cannot find id " + id);
        }
    };
    public final int id;
    public final User user;
    public final Date creationDate;
    public final Date modificationDate;
    public final Type type;
    public final String name;
    public final URI uri;

    public UserEvent(int id, User user, Type type, String name, URI uri, Date creationDate, Date modificationDate) {
        this.id = id;
        this.user = user;
        this.creationDate = creationDate;
        this.modificationDate = modificationDate;
        this.type = type;
        this.name = name;
        this.uri = uri;
    }

    public UserEvent(User user, Type type, String name, URI uri, Date creationDate, Date modificationDate, int eventId) {
        this(eventId, user, type, name, uri, creationDate, modificationDate);
    }
    public UserEvent(User user, Type type, String name, URI uri, Date creationDate, Date modificationDate) {
        this(user, type, name, uri, creationDate, modificationDate, -1);
    }

    public UserEvent(User user, Type type, String name, URI uri) {
        this(user, type, name, uri, DatashareTime.getInstance().now(), DatashareTime.getInstance().now(), -1);
    }

    @Override
    public String toString() {
        return "Name : " + name + " Uri : " + uri + " Creation Date : " + creationDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserEvent userEvent = (UserEvent) o;
        return user.equals(userEvent.user) &&
                uri.equals(userEvent.uri) &&
                creationDate.equals(userEvent.creationDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, uri, creationDate);
    }
}
