package org.icij.datashare.db;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearch.State;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchResult;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.FRENCH;

@RunWith(Parameterized.class)
public class JooqBatchSearchRepositoryTest {
    @Rule
    public DbSetupRule dbRule;
    private BatchSearchRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite://home/dev/test.sqlite")},
                {new DbSetupRule("jdbc:postgresql://postgresql/test?user=test&password=test")}
        });
    }

    public JooqBatchSearchRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createBatchSearchRepository();
    }

    @Test
    public void test_save_batch_search() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name1", "description1",
                asList("q1", "q2"), new Date());
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name2", "description2",
                asList("q3", "q4"), new Date(new Date().getTime() + 1000000000));

        repository.save(User.local(), batchSearch1);
        repository.save(User.local(), batchSearch2);

        List<BatchSearch> batchSearches = repository.get(User.local());
        assertThat(project(batchSearches, b -> b.name)).containsExactly("name2", "name1");
        assertThat(project(batchSearches, b -> b.description)).containsExactly("description2", "description1");
        assertThat(project(batchSearches, b -> b.nbResults)).containsExactly(0, 0);
        assertThat(project(batchSearches, b -> b.queries)).containsExactly(asList("q3", "q4"), asList("q1", "q2"));
    }

    @Test
    public void test_get_queued_searches() {
        repository.save(User.local(), new BatchSearch(Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date()));
        repository.save(User.local(), new BatchSearch(Project.project("prj"), "name2", "description2",
                        asList("q3", "q4"), new Date()));

        assertThat(repository.getQueued()).hasSize(2);
    }

    @Test
    public void test_get_queued_searches_without_running_state() {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.RUNNING, 0));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_success_state() {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.SUCCESS, 0));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_failure_state() {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.FAILURE, 0));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_set_state_unknown_batch_search() {
        assertThat(repository.setState("false_uuid", State.RUNNING)).isFalse();
    }

    @Test
    public void test_set_state() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description",
                asList("q1", "q2"), new Date());
        repository.save(User.local(), batchSearch);
        assertThat(repository.get(User.local()).get(0).state).isEqualTo(State.QUEUED);

        assertThat(repository.setState(batchSearch.uuid, State.RUNNING)).isTrue();

        assertThat(repository.get(User.local()).get(0).state).isEqualTo(State.RUNNING);
    }

    @Test
    public void test_save_results() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asList("my query", "my other query"));
        repository.save(User.local(), batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc1"), createDoc("doc2")))).isTrue();
        assertThat(repository.get(User.local(), batchSearch.uuid).nbResults).isEqualTo(2);

        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId).isEqualTo("id_doc1");
        assertThat(results.get(0).documentPath.toString()).isEqualTo("/path/to/doc1");
        assertThat(results.get(1).documentId).isEqualTo("id_doc2");
        assertThat(results.get(1).documentPath.toString()).isEqualTo("/path/to/doc2");
    }

    @Test
    public void test_save_results_paginated() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", singletonList("query"));
        repository.save(User.local(), batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(
                createDoc("doc1"), createDoc("doc2"), createDoc("doc3"), createDoc("doc4")))).isTrue();

        assertThat(repository.getResults(User.local(), batchSearch.uuid, 2, 0)).hasSize(2);
        assertThat(repository.getResults(User.local(), batchSearch.uuid, 2, 0)).containsExactly(
                resultFrom(createDoc("doc1"), 1), resultFrom(createDoc("doc2"), 2));
        assertThat(repository.getResults(User.local(), batchSearch.uuid, 2, 2)).containsExactly(
                resultFrom(createDoc("doc3"), 3), resultFrom(createDoc("doc4"), 4));
    }

    @Test
    public void test_get_batch_search_by_uuid() {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.RUNNING, 0));

        BatchSearch batchSearch = repository.get(User.local(), "uuid");

        assertThat(batchSearch).isNotNull();
        assertThat(batchSearch.uuid).isEqualTo("uuid");
        assertThat(batchSearch.queries).hasSize(2);
    }

    @Test
    public void test_delete_batch_searches() {
        repository.save(User.local(), new BatchSearch("uuid1", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.RUNNING, 0));
        repository.save(new User("foo"), new BatchSearch("uuid2", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.RUNNING, 0));
        repository.saveResults("uuid1", "my query", asList(
                        createDoc("doc1"), createDoc("doc2"), createDoc("doc3"), createDoc("doc4")));

        assertThat(repository.deleteBatchSearches(User.local())).isTrue();
        assertThat(repository.deleteBatchSearches(User.local())).isFalse();

        assertThat(repository.get(new User("foo"))).hasSize(1);
        assertThat(repository.get(User.local())).hasSize(0);
        assertThat(repository.getResults(User.local(), "uuid1")).hasSize(0);
    }

    @Test(expected = JooqBatchSearchRepository.UnauthorizedUserException.class)
    public void test_get_results_with_bad_user() {
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

    private SearchResult resultFrom(Document doc, int docNb) {
        return new SearchResult("my query", doc.getId(), doc.getRootDocument(), doc.getPath(), doc.getCreationDate(), docNb);
    }

    @NotNull
    private <T> List<T> project(List<BatchSearch> batchSearches, Function<BatchSearch, T> batchSearchListFunction) {
        return batchSearches.stream().map(batchSearchListFunction).collect(toList());
    }
}
