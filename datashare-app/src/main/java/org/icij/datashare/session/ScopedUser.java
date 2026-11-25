package org.icij.datashare.session;

import org.icij.datashare.text.Project;
import org.icij.datashare.user.ProjectScope;
import org.icij.datashare.user.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ScopedUser extends DatashareUser {
    private final DatashareUser delegate;
    private final Set<String> scope;

    public ScopedUser(DatashareUser user, Collection<String> projectIds) {
        super(user);
        this.delegate = user;
        this.scope = Set.copyOf(projectIds);
    }

    @Override
    public List<Project> getProjects() {
        List<Project> all = delegate.getProjects();
        return all.stream().filter(p -> scope.contains(p.getId())).collect(Collectors.toList());
    }

    @Override
    public ProjectScope scope() {
        Set<String> reachable = Set.copyOf(getProjectNames());
        return new ProjectScope.Only(reachable);
    }

    @Override
    public Map<String, Object> getDetails() {
        Map<String, Object> allDetails = super.getDetails();
        Map<String, Object> narrowed = new HashMap<>(allDetails);
        String[] keys = getJsonProjectKey().split("\\.");
        narrow(narrowed, keys, 0);
        return narrowed;
    }

    @SuppressWarnings("unchecked")
    private void narrow(Map<String, Object> node, String[] keys, int depth) {
        Object value = node.get(keys[depth]);
        boolean last = depth == keys.length - 1;
        if (last && value instanceof List<?> projects) {
            List<Object> kept = new ArrayList<>(projects);
            kept.retainAll(scope);
            node.put(keys[depth], kept);
        } else if (!last && value instanceof Map<?, ?> child) {
            Map<String, Object> copy = new HashMap<>((Map<String, Object>) child);
            node.put(keys[depth], copy);
            narrow(copy, keys, depth + 1);
        }
    }

    public static User unscoped(User user) {
        if (user instanceof ScopedUser scoped) {
            return scoped.delegate;
        }
        return user;
    }
}
