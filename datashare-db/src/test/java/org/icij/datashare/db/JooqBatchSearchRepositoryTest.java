package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.DbSetupRule.createDatasource;
import static org.jooq.SQLDialect.POSTGRES_10;
import static org.jooq.SQLDialect.SQLITE;

@RunWith(Parameterized.class)
public class JooqBatchSearchRepositoryTest {
    @Rule
    public DbSetupRule dbRule;
    private JooqBatchSearchRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() throws IOException, SQLException {
        return asList(new Object[][]{
                {createDatasource(null), SQLITE},
                {createDatasource("jdbc:postgresql://postgresql/test?user=test&password=test"), POSTGRES_10}
        });
    }

    public JooqBatchSearchRepositoryTest(DataSource dataSource, SQLDialect dialect) {
        dbRule = new DbSetupRule(dataSource);
        repository = new JooqBatchSearchRepository(new DataSourceConnectionProvider(dbRule.dataSource), dialect);
    }

    @Ignore
    @Test
    public void test_save_batch_search() throws SQLException {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name1", "description1", asList("q1", "q2"));
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name2", "description2", asList("q3", "q4"));

        repository.save(User.local(), batchSearch1);
        repository.save(User.local(), batchSearch2);

        List<BatchSearch> batchSearches = repository.get(User.local());
        assertThat(project(batchSearches, b2 -> b2.name)).containsExactly("name1", "name2");
        assertThat(project(batchSearches, b1 -> b1.description)).containsExactly("description1", "description2");
        assertThat(project(batchSearches, b -> b.queries)).containsExactly(asList("q1", "q2"), asList("q3", "q4"));
    }

    @NotNull
    private <T> List<T> project(List<BatchSearch> batchSearches, Function<BatchSearch, T> batchSearchListFunction) {
        return batchSearches.stream().map(batchSearchListFunction).collect(toList());
    }
}
