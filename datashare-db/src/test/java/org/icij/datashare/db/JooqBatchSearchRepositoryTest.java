package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearch.State;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.DbSetupRule.createDatasource;
import static org.icij.datashare.text.Language.FRENCH;
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

    @Test
    public void test_save_batch_search() throws SQLException {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name1", "description1",
                asList("q1", "q2"), new Date());
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name2", "description2",
                asList("q3", "q4"), new Date(new Date().getTime() + 1000000000));

        repository.save(User.local(), batchSearch1);
        repository.save(User.local(), batchSearch2);

        List<BatchSearch> batchSearches = repository.get(User.local());
        assertThat(project(batchSearches, b -> b.name)).containsExactly("name2", "name1");
        assertThat(project(batchSearches, b -> b.description)).containsExactly("description2", "description1");
        assertThat(project(batchSearches, b -> b.queries)).containsExactly(asList("q3", "q4"), asList("q1", "q2"));
    }

    @Test
    public void test_get_queued_searches() throws Exception {
        repository.save(User.local(), new BatchSearch(Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date()));
        repository.save(User.local(), new BatchSearch(Project.project("prj"), "name2", "description2",
                        asList("q3", "q4"), new Date()));

        assertThat(repository.getQueued()).hasSize(2);
    }

    @Test
    public void test_get_queued_searches_without_running_state() throws Exception {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.RUNNING));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_success_state() throws Exception {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.SUCCESS));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_failure_state() throws Exception {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.FAILURE));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_set_state_unknown_batch_search() throws Exception {
        assertThat(repository.setState("false_uuid", State.RUNNING)).isFalse();
    }

    @Test
    public void test_set_state() throws Exception {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description",
                asList("q1", "q2"), new Date());
        repository.save(User.local(), batchSearch);
        assertThat(repository.get(User.local()).get(0).state).isEqualTo(State.QUEUED);

        assertThat(repository.setState(batchSearch.uuid, State.RUNNING)).isTrue();

        assertThat(repository.get(User.local()).get(0).state).isEqualTo(State.RUNNING);
    }

    @Test
    public void test_save_results() throws Exception {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", singletonList("query"));
        repository.save(User.local(), batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc1"), createDoc("doc2")))).isTrue();

        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId).isEqualTo("id_doc1");
        assertThat(results.get(0).documentPath.toString()).isEqualTo("/path/to/doc1");
        assertThat(results.get(1).documentId).isEqualTo("id_doc2");
        assertThat(results.get(1).documentPath.toString()).isEqualTo("/path/to/doc2");
    }

    @Test(expected = JooqBatchSearchRepository.UnauthorizedUserException.class)
    public void test_get_results_with_bad_user() throws Exception {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", singletonList("query"));
        repository.save(User.local(), batchSearch);
        repository.saveResults(batchSearch.uuid, "query", singletonList(createDoc("doc")));

        repository.getResults(new User("hacker"), batchSearch.uuid);
    }

    private Document createDoc(String name) {
         return new Document(Project.project("prj"), "id_" + name, Paths.get("/path/to/").resolve(name), name,
                 FRENCH, Charset.defaultCharset(),
                 "text/plain", new HashMap<>(), Document.Status.INDEXED,
                 new HashSet<>(), new Date(), null, null,
                 0, 123L);
     }

    @NotNull
    private <T> List<T> project(List<BatchSearch> batchSearches, Function<BatchSearch, T> batchSearchListFunction) {
        return batchSearches.stream().map(batchSearchListFunction).collect(toList());
    }
}
