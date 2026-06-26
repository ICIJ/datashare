package org.icij.datashare.user.admin;

import org.icij.datashare.user.User;

public record UserFilter(String q) {
    public boolean isEmpty() {
        return q == null;
    }

    public boolean matches(User user) {
        if (q == null) return true;
        String lower = q.toLowerCase();
        return (user.id    != null && user.id.toLowerCase().contains(lower))
            || (user.name  != null && user.name.toLowerCase().contains(lower))
            || (user.email != null && user.email.toLowerCase().contains(lower));
    }
}
