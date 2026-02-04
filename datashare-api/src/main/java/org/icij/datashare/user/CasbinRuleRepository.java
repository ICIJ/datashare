package org.icij.datashare.user;

import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Adapter;

import java.util.List;

public interface CasbinRuleRepository extends Adapter {
    void rebuildCasbinRules();

    void loadPolicy(Model var1);

    void savePolicy(Model var1);

    void addPolicy(String var1, String var2, List<String> var3);

    void removePolicy(String var1, String var2, List<String> var3);

    void removeFilteredPolicy(String var1, String var2, int var3, String... var4);

}