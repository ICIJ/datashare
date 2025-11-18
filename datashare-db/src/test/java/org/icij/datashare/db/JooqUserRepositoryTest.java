package org.icij.datashare.db;

import junit.framework.TestCase;
import org.icij.datashare.test.DatashareTimeRule;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

import static java.util.Arrays.asList;

@RunWith(Parameterized.class)
public class JooqUserRepositoryTest extends TestCase {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2021-06-30T12:13:14Z");
    @Rule public DbSetupRule dbRule;
    private final JooqUserRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }

    public JooqUserRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createUserRepository();
    }


}