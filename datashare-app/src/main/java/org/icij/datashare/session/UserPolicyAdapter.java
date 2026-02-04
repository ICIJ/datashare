package org.icij.datashare.session;

import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.casbin.jcasbin.persist.Helper;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;

import java.util.List;

public class UserPolicyAdapter implements Adapter {


    @Override
    public void loadPolicy(Model model) {
        repository.getAllPolicies().forEach(policy -> {
            String userId = policy.userId();
            String projectId = policy.projectId();
            if (policy.isReader()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Role.READER.name()), model);
            }
            if (policy.isWriter()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Role.WRITER.name()), model);
            }
            if (policy.isAdmin()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Role.ADMIN.name()), model);
            }
        });
    }

    @Override
    public void savePolicy(Model model) {
        List<List<String>> pPolicies = model.getPolicy("p", "p");
        if (pPolicies != null) {
            for (List<String> parts : pPolicies) {
                if (parts.size() >= 3) {
                    String userId = parts.get(0);
                    String projectId = parts.get(1);
                    String roleName = parts.get(2);
                    Role role = Role.valueOf(roleName);
                    repository.save(new UserPolicy(userId, projectId, new Role[]{role}));
                }
            }
        }
        throw new UnsupportedOperationException("savePolicy not supported");

    }

    @Override
    public void addPolicy(String s, String s1, List<String> list) {
        Role[] roles = list.stream().map(Role::valueOf).toArray(Role[]::new);
        savePolicy(new UserPolicy(s, s1, roles));
    }

    @Override
    public void removePolicy(String s, String s1, List<String> list) {
        Role[] roles = list.stream().map(Role::valueOf).toArray(Role[]::new);
        repository.delete(s, s1);
    }

    @Override
    public void removeFilteredPolicy(String s, String s1, int i, String... strings) {
        throw new UnsupportedOperationException("removeFilteredPolicy not supported");
    }

}