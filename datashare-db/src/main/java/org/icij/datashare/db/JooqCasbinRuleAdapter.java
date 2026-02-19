package org.icij.datashare.db;

import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Helper;
import org.icij.datashare.CasbinRule;
import org.icij.datashare.CasbinRuleAdapter;
import org.icij.datashare.db.tables.records.CasbinRuleRecord;
import org.jooq.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.icij.datashare.db.Tables.CASBIN_RULE;
import static org.jooq.impl.DSL.using;


public class JooqCasbinRuleAdapter implements CasbinRuleAdapter {

    private static final Logger logger = LoggerFactory.getLogger(JooqCasbinRuleAdapter.class);
    private final DataSource connectionProvider;
    private final SQLDialect dialect;
    private final int batchSize;

    public JooqCasbinRuleAdapter(DataSource connectionProvider, SQLDialect dialect) {
        this(connectionProvider, dialect, determineBatchSize(dialect));
    }

    protected JooqCasbinRuleAdapter(DataSource connectionProvider, SQLDialect dialect, int batchSize) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
        this.batchSize = batchSize;
        logger.info("JooqCasbinRuleRepository initialized with dialect: {}", dialect);
    }

    @Override
    public void addPolicy(String sec, String ptype, java.util.List<String> rule) {
        List<List<String>> rules = new ArrayList<>();
        rules.add(rule);
        this.addPolicies(sec, ptype, rules);
    }


    @Override
    public void removePolicies(String sec, String ptype, List<List<String>> rules) {
        if (!rules.isEmpty()) {
            DSLContext ctx = using(connectionProvider, dialect);
            ctx.transaction((Configuration trx) -> {
                DSLContext trxCtx = using(trx);
                for (List<String> rule : rules) {
                    // Call removePolicy with transaction context
                    removePolicy(sec, ptype, rule);
                }
            });
        }
    }

    @Override
    public void removePolicy(String sec, String ptype, List<String> rule) {
        DSLContext ctx = using(connectionProvider, dialect);
        // Build and execute without wrapping in transaction
        // Let caller manage transaction context
        DeleteConditionStep<CasbinRuleRecord> deleteQuery = ctx.deleteFrom(CASBIN_RULE)
                .where(CASBIN_RULE.PTYPE.eq(ptype));

        // Add conditions for each non-empty field in the rule
        for (int i = 0; i < rule.size(); i++) {
            String value = rule.get(i);
            if (!value.isEmpty()) {
                deleteQuery = addFieldCondition(deleteQuery, i, value);
            }
        }

        int rowsDeleted = deleteQuery.execute();
        logger.debug("Removed policy: ptype={}, rowsDeleted={}", ptype, rowsDeleted);
    }

    @Override
    public void removeFilteredPolicy(String sec, String ptype, int fieldIndex, String... fieldValues) {
        List<String> values = Arrays.asList(fieldValues);
        if (!values.isEmpty()) {
            DSLContext ctx = using(connectionProvider, dialect);
            ctx.transaction((Configuration trx) -> {
                DSLContext trxCtx = using(trx);

                // Build the WHERE clause dynamically
                DeleteConditionStep<CasbinRuleRecord> deleteQuery = trxCtx.deleteFrom(CASBIN_RULE)
                        .where(CASBIN_RULE.PTYPE.eq(ptype));

                // Add conditions for non-empty field values
                int columnIndex = fieldIndex;
                for (String value : values) {
                    if (!value.isEmpty()) {
                        deleteQuery = addFieldCondition(deleteQuery, columnIndex, value);
                    }
                    columnIndex++;
                }

                int rowsDeleted = deleteQuery.execute();

                // Optional: log the deletion
                logger.debug("Removed filtered policy: section={}, ptype={}, fieldIndex={}, rowsDeleted={}",
                        sec, ptype, fieldIndex, rowsDeleted);
            });
        }
    }

    private DeleteConditionStep<CasbinRuleRecord> addFieldCondition(
            DeleteConditionStep<CasbinRuleRecord> query, int columnIndex, String value) {
        return switch (columnIndex) {
            case 0 -> query.and(CASBIN_RULE.V0.eq(value));
            case 1 -> query.and(CASBIN_RULE.V1.eq(value));
            case 2 -> query.and(CASBIN_RULE.V2.eq(value));
            case 3 -> query.and(CASBIN_RULE.V3.eq(value));
            case 4 -> query.and(CASBIN_RULE.V4.eq(value));
            case 5 -> query.and(CASBIN_RULE.V5.eq(value));
            default -> query;
        };
    }

    private static int determineBatchSize(SQLDialect dialect) {
        if (dialect.getName().contains("SQLite")) {
            return 1000;  // SQLite: prefer larger batches in single transaction
        } else if (dialect.getName().contains("Postgres")) {
            return 10000;  // PostgreSQL: optimal for JDBC batching
        }
        return 1000;  // Default fallback
    }

    private void saveSectionPolicyWithBatch(DSLContext ctx, Model model, String section) {
        if (!model.model.containsKey(section)) {
            return;
        }

        List<Query> queries = new ArrayList<>();

        for (String ptype : model.model.get(section).keySet()) {
            List<List<String>> rules = model.model.get(section).get(ptype).policy;
            createInsertQueries(ctx, queries, ptype, rules);
        }

        // Execute remaining queries
        if (!queries.isEmpty()) {
            ctx.batch(queries).execute();
        }
    }

    private void createInsertQueries(DSLContext ctx, List<Query> queries, String ptype, List<List<String>> rules) {
        for (List<String> rule : rules) {
            CasbinRule line = savePolicyLine(ptype, rule);
            queries.add(
                    ctx.insertInto(CASBIN_RULE, CASBIN_RULE.PTYPE, CASBIN_RULE.V0, CASBIN_RULE.V1, CASBIN_RULE.V2, CASBIN_RULE.V3, CASBIN_RULE.V4, CASBIN_RULE.V5)
                            .values(line.ptype, line.v0, line.v1, line.v2, line.v3, line.v4, line.v5)
            );

            // Execute batch immediately when reaching batch size
            if (queries.size() == batchSize) {
                ctx.batch(queries).execute();
                queries.clear();
            }
        }
    }

    @Override
    public void savePolicy(Model model) {
        DSLContext ctx = using(connectionProvider, dialect);
        ctx.transaction((Configuration trx) -> {
            DSLContext trxCtx = using(trx);

            // Delete all existing rules
            trxCtx.deleteFrom(CASBIN_RULE).execute();

            // Process both sections with immediate batch execution
            saveSectionPolicyWithBatch(trxCtx, model, "p");
            saveSectionPolicyWithBatch(trxCtx, model, "g");
            saveSectionPolicyWithBatch(trxCtx, model, "g2");
        });
    }

    public void addPolicies(String sec, String ptype, List<List<String>> rules) {
        DSLContext ctx = using(connectionProvider, dialect);
        ctx.transaction((Configuration trx) -> {
            DSLContext trxCtx = using(trx);

            List<Query> queries = new ArrayList<>();

            createInsertQueries(trxCtx, queries, ptype, rules);

            // Execute remaining queries
            if (!queries.isEmpty()) {
                trxCtx.batch(queries).execute();
            }
        });
    }

    private CasbinRule savePolicyLine(String ptype, List<String> rule) {
        CasbinRule line = new CasbinRule();
        line.ptype = ptype;
        if (!rule.isEmpty()) {
            line.v0 = rule.get(0);
        }

        if (rule.size() > 1) {
            line.v1 = rule.get(1);
        }

        if (rule.size() > 2) {
            line.v2 = rule.get(2);
        }

        if (rule.size() > 3) {
            line.v3 = rule.get(3);
        }

        if (rule.size() > 4) {
            line.v4 = rule.get(4);
        }

        if (rule.size() > 5) {
            line.v5 = rule.get(5);
        }

        return line;
    }

    @Override
    public void loadPolicy(Model model) {
        DSLContext ctx = using(connectionProvider, dialect);
        List<CasbinRuleRecord> records = ctx.selectFrom(CASBIN_RULE).fetch();
        for (CasbinRuleRecord record : records) {
            org.icij.datashare.CasbinRule line = new org.icij.datashare.CasbinRule();
            line.ptype = record.get(CASBIN_RULE.PTYPE);
            line.v0 = record.get(CASBIN_RULE.V0) != null ? record.get(CASBIN_RULE.V0) : "";
            line.v1 = record.get(CASBIN_RULE.V1) != null ? record.get(CASBIN_RULE.V1) : "";
            line.v2 = record.get(CASBIN_RULE.V2) != null ? record.get(CASBIN_RULE.V2) : "";
            line.v3 = record.get(CASBIN_RULE.V3) != null ? record.get(CASBIN_RULE.V3) : "";
            line.v4 = record.get(CASBIN_RULE.V4) != null ? record.get(CASBIN_RULE.V4) : "";
            line.v5 = record.get(CASBIN_RULE.V5) != null ? record.get(CASBIN_RULE.V5) : "";
            loadPolicyLine(line, model);
        }

    }

    protected void loadPolicyLine(org.icij.datashare.CasbinRule line, Model model) {
        // Escape ONLY for Casbin's line format, NOT for database storage
        CasbinRule escapedLine = org.icij.datashare.CasbinRule.escape(line);

        // Build the text line for Casbin
        String lineText = CasbinRule.getLineText(escapedLine);

        Helper.loadPolicyLine(lineText, model);
    }

    @Override
    public void updatePolicy(String sec, String ptype, List<String> oldRule, List<String> newRule) {
        if (!oldRule.isEmpty() && !newRule.isEmpty()) {
            DSLContext ctx = using(connectionProvider, dialect);
            ctx.transaction((Configuration trx) -> {
                DSLContext trxCtx = using(trx);

                // Remove the old rule using the DSLContext from transaction
                DeleteConditionStep<CasbinRuleRecord> deleteQuery = trxCtx.deleteFrom(CASBIN_RULE)
                        .where(CASBIN_RULE.PTYPE.eq(ptype));
                for (int i = 0; i < oldRule.size(); i++) {
                    String value = oldRule.get(i);
                    if (!value.isEmpty()) {
                        deleteQuery = addFieldCondition(deleteQuery, i, value);
                    }
                }
                deleteQuery.execute();

                // Insert the new rule
                org.icij.datashare.CasbinRule line = savePolicyLine(ptype, newRule);
                trxCtx.insertInto(CASBIN_RULE, CASBIN_RULE.PTYPE, CASBIN_RULE.V0, CASBIN_RULE.V1, CASBIN_RULE.V2, CASBIN_RULE.V3, CASBIN_RULE.V4, CASBIN_RULE.V5)
                        .values(line.ptype, line.v0, line.v1, line.v2, line.v3, line.v4, line.v5)
                        .execute();

                logger.debug("Updated policy: ptype={}", ptype);
            });
        }
    }
}