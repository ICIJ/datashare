package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.icij.datashare.user.User;

import java.util.Date;
import java.util.Objects;

import static org.icij.datashare.user.User.nullUser;

public class Tag {
    public final String label;
    public final Date creationDate;
    public final User user;


    public Tag(final String label) {
        this(label, nullUser());
    }

    public Tag(final String label, User user) {
        this(label, user, new Date());
    }

    public Tag(final String label, User user, Date creationDate) {
        this.label = label;
        this.user = user;
        this.creationDate = creationDate;
    }

    @JsonCreator
    public static Tag tag(String label) {
        return new Tag(label);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tag tag = (Tag) o;
        return Objects.equals(label, tag.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label);
    }

    @Override
    public String toString() { return "Tag{label='" + label + '\'' + '}';}
}
