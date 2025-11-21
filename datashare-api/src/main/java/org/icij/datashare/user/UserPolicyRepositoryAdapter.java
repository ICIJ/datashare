package org.icij.datashare.user;

import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.casbin.jcasbin.persist.Helper;

import java.util.List;

public class UserPolicyRepositoryAdapter implements Adapter {

    private final UserRepository repository;

    public UserPolicyRepositoryAdapter(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    public void loadPolicy(Model model) {
        List<UserPolicy> policies = repository.getAll();
        if (policies == null || policies.isEmpty()) return;
        for (UserPolicy policy : policies) {
            String userId = policy.userId();
            String projectId = policy.projectId();
            if (policy.reader()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Role.READER.name()), model);
            }
            if (policy.writer()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Role.WRITER.name()), model);
            }
            if (policy.admin()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, %s", userId, projectId, Role.ADMIN.name()), model);
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
