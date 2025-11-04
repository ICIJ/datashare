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

    @Override
    public void loadPolicy(Model model) {
        List<UserPolicy> policies = repository.getAll();
        if (policies == null || policies.isEmpty()) return;
        for (UserPolicy policy : policies) {
            String userId = policy.userId();
            String projectId = policy.projectId();
            if (policy.read()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, read", userId, projectId), model);
            }
            if (policy.write()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, write", userId, projectId), model);
            }
            if (policy.admin()) {
                Helper.loadPolicyLine(String.format("p, %s, %s, admin", userId, projectId), model);
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
