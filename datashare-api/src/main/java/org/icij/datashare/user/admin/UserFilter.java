package org.icij.datashare.user.admin;

import org.icij.datashare.user.User;

import java.util.List;
import java.util.Map;

public record UserFilter(
        String name,
        String email,
        String provider,
        String group
) {
    public boolean isEmpty() {
        return name == null && email == null && provider == null && group == null;
    }

    public boolean matches(User user) {
        if (name != null && (user.name == null
                || !user.name.toLowerCase().contains(name.toLowerCase()))) {
            return false;
        }
        if (email != null && (user.email == null
                || !user.email.toLowerCase().contains(email.toLowerCase()))) {
            return false;
        }
        if (provider != null && !provider.equals(user.provider)) {
            return false;
        }
        if (group != null) {
            Object appsByGroup = user.details == null ? null
                    : user.details.get("groups_by_applications");
            if (!(appsByGroup instanceof Map<?, ?> map)) return false;
            Object datashare = map.get("datashare");
            if (!(datashare instanceof List<?> list)) return false;
            return list.stream().anyMatch(
                    g -> g.toString().toLowerCase().contains(group.toLowerCase()));
        }
        return true;
    }
}
