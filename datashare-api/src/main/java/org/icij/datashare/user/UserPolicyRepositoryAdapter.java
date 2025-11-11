package org.icij.datashare.user;

import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.casbin.jcasbin.persist.Helper;

import java.util.List;

public class UserPolicyRepositoryAdapter implements Adapter {

    private final UserPolicyRepository repository;

    public UserPolicyRepositoryAdapter(UserPolicyRepository repository) {
        this.repository = repository;
    }
    public enum Permission {
        READ("read"),
        WRITE("write"),
        ADMIN("admin");

        private final String value;

        Permission(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    @Override
    public void loadPolicy(Model model) {
        List<UserPolicy> policies = repository.getAll();
        if (policies == null || policies.isEmpty()) return;
        for (UserPolicy policy : policies) {
            String userId = policy.userId();
            String projectId = policy.projectId();
            if (policy.read()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Permission.READ.value()), model);
            }
            if (policy.write()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Permission.WRITE.value()), model);
            }
            if (policy.admin()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Permission.ADMIN.value()), model);
            }
        }

    }

    @Override
    public void savePolicy(Model model) {
        throw new UnsupportedOperationException("savePolicy not supported");

    }

    @Override
    public void addPolicy(String s, String s1, List<String> list) {
        throw new UnsupportedOperationException("addPolicy not supported");
    }

    @Override
    public void removePolicy(String s, String s1, List<String> list) {
        throw new UnsupportedOperationException("removePolicy not supported");

    }

    @Override
    public void removeFilteredPolicy(String s, String s1, int i, String... strings) {
        throw new UnsupportedOperationException("removeFilteredPolicy not supported");
    }

}
