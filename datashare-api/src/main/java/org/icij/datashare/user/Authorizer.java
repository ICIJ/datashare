package org.icij.datashare.user;

import org.casbin.jcasbin.main.Enforcer;
import org.casbin.jcasbin.model.Model;

import java.util.UUID;

public class Authorizer {
    private final Enforcer enforcer;
    private final CasbinRuleRepository repository;

    public Authorizer(CasbinRuleRepository repository, String modelPath) {
        this.repository = repository;
        Model model = new Model();
        model.loadModel(modelPath);
        this.enforcer = new Enforcer(model, repository);
    }

    public boolean can(UUID userId, UUID domainId, UUID projectId, String action) {
        return enforcer.enforce(userId.toString(), domainId.toString(), projectId.toString(), action);
    }

    public void rebuild() {
        repository.rebuildCasbinRules();
        enforcer.loadPolicy();
    }
}
