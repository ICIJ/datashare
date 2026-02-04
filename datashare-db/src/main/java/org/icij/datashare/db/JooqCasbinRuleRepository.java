package org.icij.datashare.db;

import org.casbin.jcasbin.model.Model;
import org.icij.datashare.user.CasbinRuleRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;

import javax.sql.DataSource;
import java.util.List;

import static org.icij.datashare.db.Tables.*;
import static org.jooq.impl.DSL.using;

public class JooqCasbinRuleRepository implements CasbinRuleRepository {

    private final DataSource connectionProvider;
    private final SQLDialect dialect;

    JooqCasbinRuleRepository(final DataSource connectionProvider, final SQLDialect dialect) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
    }

    @Override
    public void rebuildCasbinRules() {
        DSLContext ctx = using(connectionProvider, dialect);

        ctx.transaction(configuration -> {
            DSLContext tCtx = using(configuration);
            // wipe execution table
            tCtx.deleteFrom(CASBIN_RULE).execute();

            // g: user -> role -> domain
            tCtx.selectFrom(USER_DOMAIN_ROLE)
                    .fetch()
                    .forEach(r ->
                            tCtx.insertInto(CASBIN_RULE)
                                    .set(CASBIN_RULE.PTYPE, "g")
                                    .set(CASBIN_RULE.V0, r.getUserId().toString())
                                    .set(CASBIN_RULE.V1, r.getRole())
                                    .set(CASBIN_RULE.V2, r.getDomainId().toString())
                                    .execute()
                    );

            // p: role -> domain -> project -> action
            tCtx.selectFrom(ROLE_PROJECT_PERMISSION)
                    .fetch()
                    .forEach(r ->
                            tCtx.insertInto(CASBIN_RULE)
                                    .set(CASBIN_RULE.PTYPE, "p")
                                    .set(CASBIN_RULE.V0, r.getRole())
                                    .set(CASBIN_RULE.V1, r.getDomainId().toString())
                                    .set(CASBIN_RULE.V2, r.getProjectId().toString())
                                    .set(CASBIN_RULE.V3, r.getAction())
                                    .execute()
                    );
        });
    }

    @Override
    public void loadPolicy(Model model) {
        DSLContext ctx = using(connectionProvider, dialect);
        ctx.selectFrom(CASBIN_RULE)
                .fetch()
                .forEach(r -> {
                    String ptype = r.getPtype();
                    String sec = ptype.substring(0, 1);
                    java.util.List<String> rule = new java.util.ArrayList<>();
                    if (r.getV0() != null) rule.add(r.getV0());
                    if (r.getV1() != null) rule.add(r.getV1());
                    if (r.getV2() != null) rule.add(r.getV2());
                    if (r.getV3() != null) rule.add(r.getV3());
                    if (r.getV4() != null) rule.add(r.getV4());
                    if (r.getV5() != null) rule.add(r.getV5());
                    model.addPolicy(sec, ptype, rule);
                });
    }

    @Override
    public void savePolicy(Model var1) {

    }

    @Override
    public void addPolicy(String var1, String var2, List<String> var3) {

    }

    @Override
    public void removePolicy(String var1, String var2, List<String> var3) {

    }

    @Override
    public void removeFilteredPolicy(String var1, String var2, int var3, String... var4) {

    }

}