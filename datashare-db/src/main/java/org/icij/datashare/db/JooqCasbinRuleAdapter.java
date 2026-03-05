package org.icij.datashare.db;

import org.casbin.jcasbin.exception.CasbinAdapterException;
import org.casbin.jcasbin.model.Model;
import org.casbin.jcasbin.persist.Helper;
import org.casbin.jcasbin.persist.file_adapter.FilteredAdapter.Filter;
import org.icij.datashare.db.tables.records.CasbinRuleRecord;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.jooq.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.icij.datashare.db.Tables.CASBIN_RULE;
import static org.jooq.impl.DSL.trueCondition;
import static org.jooq.impl.DSL.using;

public class JooqCasbinRuleAdapter implements CasbinRuleAdapter {

    private static final Logger logger = LoggerFactory.getLogger(JooqCasbinRuleAdapter.class);
    private final DataSource connectionProvider;
    private final SQLDialect dialect;
    private final int batchSize;
    private volatile boolean isFiltered = false;

    public JooqCasbinRuleAdapter(DataSource connectionProvider, SQLDialect dialect) {
        this(connectionProvider, dialect, determineBatchSize(dialect));
    }

    protected JooqCasbinRuleAdapter(DataSource connectionProvider, SQLDialect dialect, int batchSize) {
        this.connectionProvider = connectionProvider;
        this.dialect = dialect;
        this.batchSize = batchSize;
    }

    private static int determineBatchSize(SQLDialect dialect) {
        if (dialect.getName().contains("SQLite")) {
            return 1000;  // SQLite: prefer larger batches in single transaction
        } else if (dialect.getName().contains("Postgres")) {
            return 10000;  // PostgreSQL: optimal for JDBC batching
        }
        return 1000;  // Default fallback
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
                    removePolicy(trxCtx, ptype, rule);
                }
            });
        }
    }

    @Override
    public void removePolicy(String sec, String ptype, List<String> rule) {
        removePolicy(using(connectionProvider, dialect), ptype, rule);
    }

    private void removePolicy(DSLContext ctx, String ptype, List<String> rule) {
        DeleteConditionStep<CasbinRuleRecord> deleteQuery = ctx.deleteFrom(CASBIN_RULE)
                .where(CASBIN_RULE.PTYPE.eq(ptype));
        for (int i = 0; i < rule.size(); i++) {
            String value = rule.get(i);
            if (!value.isEmpty()) {
                deleteQuery = addFieldCondition(deleteQuery, i, value);
            }
        }
        deleteQuery.execute();
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

                deleteQuery.execute();
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
                            .values(line.getPtype(), line.getV0(), line.getV1(), line.getV2(), line.getV3(), line.getV4(), line.getV5())
                            .onConflictDoNothing()
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

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    @Override
    public void loadPolicy(Model model) {
        DSLContext ctx = using(connectionProvider, dialect);
        List<CasbinRuleRecord> records = ctx.selectFrom(CASBIN_RULE).fetch();
        loadCasbinRuleRecords(model, records);
    }

    protected void loadPolicyLine(CasbinRule line, Model model) {
        // Escape ONLY for Casbin's line format, NOT for database storage
        CasbinRule escapedLine = CasbinRule.escape(line);

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
                CasbinRule line = savePolicyLine(ptype, newRule);
                trxCtx.insertInto(CASBIN_RULE, CASBIN_RULE.PTYPE, CASBIN_RULE.V0, CASBIN_RULE.V1, CASBIN_RULE.V2, CASBIN_RULE.V3, CASBIN_RULE.V4, CASBIN_RULE.V5)
                        .values(line.getPtype(), line.getV0(), line.getV1(), line.getV2(), line.getV3(), line.getV4(), line.getV5())
                        .execute();

                logger.debug("Updated policy: ptype={}", ptype);
            });
        }
    }

    @Override
    public void loadFilteredPolicy(Model model, Object filter) throws CasbinAdapterException {
        if (filter == null) {
            loadPolicy(model);
            isFiltered = false;
            return;
        }
        if (!(filter instanceof Filter f)) {
            isFiltered = false;
            throw new CasbinAdapterException("Invalid filter type.");
        }
        isFiltered = true;
        DSLContext ctx = using(connectionProvider, dialect);
        // Handle each section: p, g, g2
        loadFilteredSectionPolicy(ctx, model, "p", f.p);
        loadFilteredSectionPolicy(ctx, model, "g", f.g);
        // Only check g2 if present in the Filter class
        try {
            java.lang.reflect.Field g2Field = f.getClass().getDeclaredField("g2");
            g2Field.setAccessible(true);
            Object g2Value = g2Field.get(f);
            if (g2Value instanceof String[] g2Arr) {
                loadFilteredSectionPolicy(ctx, model, "g2", g2Arr);
            }
        } catch (NoSuchFieldException ignored) {
            // g2 is an optional field; absence is not an error
        } catch (IllegalAccessException e) {
            throw new CasbinAdapterException("Failed to access g2 field on filter: " + e.getMessage());
        }
    }

    private void loadFilteredSectionPolicy(DSLContext ctx, Model model, String section, String[] filterSlice) {
        if (filterSlice == null) return;
        // Build query for section and filterSlice
        Condition condition = CASBIN_RULE.PTYPE.eq(section);
        for (int i = 0; i < filterSlice.length; i++) {
            String value = filterSlice[i];
            if (value != null && !value.isEmpty()) {
                condition = condition.and(getFieldCondition(i, value));
            }
        }
        List<CasbinRuleRecord> records = ctx.selectFrom(CASBIN_RULE).where(condition).fetch();
        loadCasbinRuleRecords(model, records);
    }

    private CasbinRule savePolicyLine(String ptype, List<String> rule) {
        return new CasbinRule(ptype, rule.toArray(new String[0]));
    }

    private void loadCasbinRuleRecords(Model model, List<CasbinRuleRecord> records) {
        for (CasbinRuleRecord record : records) {
            CasbinRule line = new CasbinRule(
                    record.get(CASBIN_RULE.PTYPE),
                    nullToEmpty(record.get(CASBIN_RULE.V0)),
                    nullToEmpty(record.get(CASBIN_RULE.V1)),
                    nullToEmpty(record.get(CASBIN_RULE.V2)),
                    nullToEmpty(record.get(CASBIN_RULE.V3)),
                    nullToEmpty(record.get(CASBIN_RULE.V4)),
                    nullToEmpty(record.get(CASBIN_RULE.V5))
            );
            loadPolicyLine(line, model);
        }
    }

    private Condition getFieldCondition(int columnIndex, String value) {
        return switch (columnIndex) {
            case 0 -> CASBIN_RULE.V0.eq(value);
            case 1 -> CASBIN_RULE.V1.eq(value);
            case 2 -> CASBIN_RULE.V2.eq(value);
            case 3 -> CASBIN_RULE.V3.eq(value);
            case 4 -> CASBIN_RULE.V4.eq(value);
            case 5 -> CASBIN_RULE.V5.eq(value);
            default -> trueCondition();
        };
    }

    @Override
    public boolean isFiltered() {
        return isFiltered;
    }
}
