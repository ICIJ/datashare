package org.icij.datashare.session;

import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;
import org.casbin.jcasbin.persist.Helper;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicyRepository;

import java.util.List;

public class UserPolicyAdapter implements Adapter {

    private final UserPolicyRepository repository;

    public UserPolicyAdapter(UserPolicyRepository repository) {
        this.repository = repository;
    }

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