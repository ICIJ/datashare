package org.icij.datashare.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.Tables.EXTRACTION_MAPPING;
import static org.icij.datashare.db.Tables.STATEMENT;

@RunWith(Parameterized.class)
public class JooqStatementRepositoryTest {
    @Rule public DbSetupRule dbRule;

    @Test
    public void test_statement_table_exists() {
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(0);
    }

    @Test
    public void test_extraction_mapping_table_exists() {
        assertThat(dbRule.dsl().fetchCount(EXTRACTION_MAPPING)).isEqualTo(0);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {DbTestRuleProvider.getSqliteRule()},
                {DbTestRuleProvider.getPostgresRule()}
        });
    }

    public JooqStatementRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
    }
}
