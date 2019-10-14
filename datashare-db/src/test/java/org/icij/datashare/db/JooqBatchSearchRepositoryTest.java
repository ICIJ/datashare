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

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

@RunWith(Parameterized.class)
public class JooqBatchSearchRepositoryTest {
    @Rule
    public DbSetupRule dbRule;
    private BatchSearchRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgresql/test?user=test&password=test")}
        });
    }

    public JooqBatchSearchRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createBatchSearchRepository();
    }

    @Test
    public void test_save_and_get_batch_search() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name1", "description1",
                asList("q1", "q2"), true, asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name2", "description2",
                asList("q3", "q4"), new Date(new Date().getTime() + 1000000000));

        repository.save(User.local(), batchSearch1);
        repository.save(User.local(), batchSearch2);

        List<BatchSearch> batchSearches = repository.get(User.local());

        assertThat(project(batchSearches, b -> b.name)).containsExactly("name2", "name1");
        assertThat(project(batchSearches, b -> b.published)).containsExactly(false, true);
        assertThat(project(batchSearches, b -> b.fileTypes)).containsExactly(emptyList(), asList("application/json", "image/jpeg"));
        assertThat(project(batchSearches, b -> b.paths)).containsExactly(emptyList(), asList("/path/to/docs", "/path/to/pdfs"));
        assertThat(project(batchSearches, b -> b.fuzziness)).containsExactly(0, 3);
        assertThat(project(batchSearches, b -> b.description)).containsExactly("description2", "description1");
        assertThat(project(batchSearches, b -> b.nbResults)).containsExactly(0, 0);
        assertThat(project(batchSearches, b ->b.phraseMatches)).containsExactly(false,true);
        assertThat(project(batchSearches, BatchSearch::getQueryList)).containsExactly(asList("q3", "q4"), asList("q1", "q2"));

        assertThat(project(repository.get(new User("other")), b -> b.name)).containsExactly("name1");
    }

    @Test
    public void test_get_with_projects() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj1"), "name1", "description1",
                asList("q1", "q2"), true, asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj2"), "name2", "description2",
                asList("q3", "q4"), new Date(new Date().getTime() + 1000000000));

        repository.save(User.local(), batchSearch1);
        repository.save(User.local(), batchSearch2);

        assertThat(repository.get(User.local(), asList("prj1"))).containsExactly(batchSearch1);
        assertThat(repository.get(User.local(), asList("prj2"))).containsExactly(batchSearch2);
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
                        asList("q1", "q2"), new Date(), State.RUNNING));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_success_state() {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.SUCCESS));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_failure_state() {
        repository.save(User.local(), new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                        asList("q1", "q2"), new Date(), State.FAILURE));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_set_state_unknown_batch_search() {
        assertThat(repository.setState("false_uuid", State.RUNNING)).isFalse();
    }

    @Test
    public void test_set_state() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asList("q1", "q2"), new Date());
        repository.save(User.local(), batchSearch);

        assertThat(repository.get(User.local()).get(0).state).isEqualTo(State.QUEUED);
        assertThat(repository.setState(batchSearch.uuid, State.RUNNING)).isTrue();
        assertThat(repository.get(User.local()).get(0).state).isEqualTo(State.RUNNING);
    }

    @Test
    public void test_save_results() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asList("my query", "my other query"));
        repository.save(User.local(), batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()))).isTrue();
        assertThat(repository.get(User.local(), batchSearch.uuid).nbResults).isEqualTo(2);
        assertThat(repository.get(User.local(), batchSearch.uuid).queries).includes(entry("my query", 2), entry("my other query", 0));

        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId).isEqualTo("doc1");
        assertThat(results.get(0).documentName).isEqualTo("doc1");
        assertThat(results.get(1).documentId).isEqualTo("doc2");
        assertThat(results.get(1).documentName).isEqualTo("doc2");
    }

    @Test
    public void test_results_by_query_are_isolated() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name1", "description1", asList("my query", "my other query"));
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name2", "description2", asList("my query", "3rd query"));
        repository.save(User.local(), batchSearch1);
        repository.save(User.local(), batchSearch2);
        repository.saveResults(batchSearch1.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()));

        assertThat(repository.get(User.local(), batchSearch2.uuid).queries).includes(entry("my query", 0), entry("3rd query", 0));
    }

    @Test
    public void test_get_results_paginated() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", singletonList("query"));
        repository.save(User.local(), batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "query", asList(
                createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()))).isTrue();

        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(2, 0))).hasSize(2);
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(2, 0))).containsExactly(
                resultFrom(createDoc("doc1").build(), 1, "query"), resultFrom(createDoc("doc2").build(), 2, "query"));
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery( 2, 2))).containsExactly(
                resultFrom(createDoc("doc3").build(), 3, "query"), resultFrom(createDoc("doc4").build(), 4, "query"));
    }

    @Test
    public void test_get_results_filtered_by_query() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asList("q1", "q2"));
        repository.save(User.local(), batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("doc1").build(), createDoc("doc2").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("doc3").build(), createDoc("doc4").build()));

        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, null, null, singletonList("q1")));
        assertThat(results).hasSize(2);
        assertThat(results).containsExactly(
                resultFrom(createDoc("doc1").build(), 1, "q1"),
                resultFrom(createDoc("doc2").build(), 2, "q1")
        );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, null, null, singletonList("q2")))).
                hasSize(2);
    }

    @Test
    public void test_get_results_order() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asList("q1", "q2"));
        repository.save(User.local(), batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("a").build(), createDoc("c").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("b").build(), createDoc("d").build()));

        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0))).
                containsExactly(
                        resultFrom(createDoc("a").build(), 1, "q1"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("d").build(), 2, "q2")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, "doc_name", "asc", null))).
                containsExactly(
                        resultFrom(createDoc("a").build(), 1, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("d").build(), 2, "q2")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, "doc_name", "desc", null))).
                containsExactly(
                        resultFrom(createDoc("d").build(), 2, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("a").build(), 1, "q1")
                );
    }

    @Test
    public void test_get_batch_search_by_uuid() {
        BatchSearch search = new BatchSearch(Project.project("prj"), "name1", "description1", asList("q1", "q2"));
        repository.save(User.local(), search);

        BatchSearch batchSearch = repository.get(User.local(), search.uuid);

        assertThat(batchSearch).isNotNull();
        assertThat(batchSearch.queries).hasSize(2);
    }

    @Test
    public void test_delete_batch_searches() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name", "description1", asList("q1", "q2"));
        repository.save(User.local(), batchSearch1);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name", "description3", asList("q3", "q4"));
        repository.save(new User("foo"), batchSearch2);
        repository.saveResults(batchSearch1.uuid, "q2", asList(
                        createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.deleteAll(User.local())).isTrue();
        assertThat(repository.deleteAll(User.local())).isFalse();

        assertThat(repository.get(new User("foo"))).hasSize(1);
        assertThat(repository.get(User.local())).hasSize(0);
        assertThat(repository.getResults(User.local(), batchSearch1.uuid)).hasSize(0);
    }

    @Test
    public void test_delete_batch_search() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name", "description1", asList("q1", "q2"));
        repository.save(User.local(), batchSearch1);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name", "description3", asList("q3", "q4"));
        repository.save(new User("foo"), batchSearch2);
        repository.saveResults(batchSearch1.uuid, "q2", asList(
                        createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.delete(User.local(), batchSearch1.uuid)).isTrue();
        assertThat(repository.delete(User.local(), batchSearch1.uuid)).isFalse();

        assertThat(repository.get(new User("foo"))).hasSize(1);
        assertThat(repository.get(User.local())).isEmpty();
        assertThat(repository.getResults(User.local(), batchSearch1.uuid)).hasSize(0);
    }

    @Test(expected = JooqBatchSearchRepository.UnauthorizedUserException.class)
    public void test_get_results_with_bad_user() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", singletonList("query"));
        repository.save(User.local(), batchSearch);
        repository.saveResults(batchSearch.uuid, "query", singletonList(createDoc("doc").build()));

        repository.getResults(new User("hacker"), batchSearch.uuid);
    }

    @Test
    public void test_get_results_published_from_another_user() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", singletonList("query"), true);
        repository.save(User.local(), batchSearch);
        repository.saveResults(batchSearch.uuid, "query", asList(
              createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.getResults(new User("other"), batchSearch.uuid, new BatchSearchRepository.WebQuery(2, 0))).hasSize(2);
    }

    private SearchResult resultFrom(Document doc, int docNb, String queryName) {
        return new SearchResult(queryName, doc.getId(), doc.getRootDocument(), doc.getPath().getFileName().toString(), doc.getCreationDate(), doc.getContentType(), doc.getContentLength(), docNb);
    }

    @NotNull
    private <T> List<T> project(List<BatchSearch> batchSearches, Function<BatchSearch, T> batchSearchListFunction) {
        return batchSearches.stream().map(batchSearchListFunction).collect(toList());
    }
}
