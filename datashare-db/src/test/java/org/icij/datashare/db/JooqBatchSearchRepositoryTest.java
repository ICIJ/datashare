package org.icij.datashare.db;

import org.icij.datashare.batch.*;
import org.icij.datashare.batch.BatchSearchRecord.State;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

@RunWith(Parameterized.class)
public class JooqBatchSearchRepositoryTest {
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-08-04T10:20:30Z");
    @Rule public DbSetupRule dbRule;
    @Rule public TemporaryFolder dataFolder = new TemporaryFolder();
    private final BatchSearchRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/test?user=test&password=test")}
        });
    }

    public JooqBatchSearchRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createBatchSearchRepository();
    }

    @Test
    public void test_save_and_get_batch_search() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name1", "description1",
                asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),
                3,true);

        repository.save(batchSearch);

        BatchSearch batchSearchFromGet = repository.get(batchSearch.uuid);

        assertThat(batchSearchFromGet.name).isEqualTo(batchSearch.name);
        assertThat(batchSearchFromGet.published).isEqualTo(batchSearch.published);
        assertThat(batchSearchFromGet.fileTypes).isEqualTo(batchSearch.fileTypes);
        assertThat(batchSearchFromGet.paths).isEqualTo(batchSearch.paths);
        assertThat(batchSearchFromGet.fuzziness).isEqualTo(batchSearch.fuzziness);
        assertThat(batchSearchFromGet.description).isEqualTo(batchSearch.description);
        assertThat(batchSearchFromGet.nbResults).isEqualTo(batchSearch.nbResults);
        assertThat(batchSearchFromGet.phraseMatches).isEqualTo(batchSearch.phraseMatches);
        assertThat(batchSearchFromGet.queries).isEqualTo(batchSearch.queries);

        assertThat(batchSearchFromGet.user).isEqualTo(User.local());
    }

    @Test
    public void test_get_records_filter_by_project() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj1"), "name1", "description1",
                asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj2"), "name2", "description2",
                asSet("q3", "q4"), new Date(new Date().getTime() + 1000000000));

        repository.save(batchSearch1);
        repository.save(batchSearch2);

        assertThat(repository.getRecords(User.local(), singletonList("prj1")).get(0).uuid).isEqualTo(batchSearch1.uuid);
        assertThat(repository.getRecords(User.local(), singletonList("prj2")).get(0).uuid).isEqualTo(batchSearch2.uuid);
    }

    @Test
    public void test_get_total_filter_by_project() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj1"), "name1", "description1",
                        asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj2"), "name2", "description2",
                asSet("q3", "q4"), User.local(), true);

        repository.save(batchSearch1);
        repository.save(batchSearch2);

        assertThat(repository.getTotal(User.local(), asList("prj1", "prj2"))).isEqualTo(2);
        assertThat(repository.getTotal(User.local(), singletonList("prj1"))).isEqualTo(1);
        assertThat(repository.getTotal(User.local(), singletonList("prj3"))).isEqualTo(0);
    }

    @Test
    public void test_get_records() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"),"name","description",asSet("q1", "q2"), User.local(),
                true,asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        repository.save(batchSearch);

        List<BatchSearchRecord> batchSearchRecords = repository.getRecords(User.local(), singletonList("prj"));
        assertThat(batchSearchRecords).hasSize(1);
        BatchSearchRecord actual = batchSearchRecords.get(0);
        assertThat(actual.getNbQueries()).isEqualTo(2);
        assertThat(actual.name).isEqualTo(batchSearch.name);
        assertThat(actual.description).isEqualTo(batchSearch.description);
        assertThat(actual.project).isEqualTo(batchSearch.project);
        assertThat(actual.user).isEqualTo(batchSearch.user);
        assertThat(actual.published).isEqualTo(batchSearch.published);
        assertThat(actual.date).isEqualTo(batchSearch.date);
        assertThat(actual.state).isEqualTo(batchSearch.state);
        assertThat(actual.nbResults).isEqualTo(batchSearch.nbResults);
        assertThat(actual.errorMessage).isEqualTo(batchSearch.errorMessage);
    }

    @Test
    public void test_get_records_with_query_and_field() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"),"foo","baz",asSet("q1", "q2"), User.local(),
                true,asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"),"bar","baz",asSet("q3", "q2"), User.local(),
                true,asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        repository.save(batchSearch1);
        repository.save(batchSearch2);
        assertThat(repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(0, 0,null,null,"*","all",null))).hasSize(2);
        assertThat(repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(0, 0,null,null,"foo","all",null))).hasSize(1);
        assertThat(repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(0, 0,null,null,"oo","all",null))).hasSize(1);
        assertThat(repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(0, 0,null,null,"baz","description",null))).hasSize(2);
        assertThat(repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(0, 0,null,null,"baz","name",null))).hasSize(0);
        assertThat(repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(0, 0,null,null,"","all",null))).hasSize(2);
    }

    @Test
    public void test_get_records_with_query() {
        IntStream.range(0, 5).mapToObj(i -> new BatchSearch(Project.project("prj"), "name" + i, "description" + i, asSet("q1/" + i, "q2/" + i), User.local(),
                true, asList("application/json", "image/jpeg"), asList("/path/to/docs", "/path/to/pdfs"), 3, true)).
                forEach(bs -> {
                            repository.save(bs);
                            DatashareTime.getInstance().addMilliseconds(1000);
                        }
                );

        List<BatchSearchRecord> from0To2 = repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(2, 0));
        assertThat(from0To2).hasSize(2);
        assertThat(from0To2.get(0).name).isEqualTo("name4");
        assertThat(from0To2.get(1).name).isEqualTo("name3");

        List<BatchSearchRecord> from0To2OrderByName = repository.getRecords(User.local(), singletonList("prj"), new BatchSearchRepository.WebQuery(1, 1, "name", "asc","*","all", null));
        assertThat(from0To2OrderByName).hasSize(1);
        assertThat(from0To2OrderByName.get(0).name).isEqualTo("name1");
    }

    @Test
    public void test_get_queued_searches() {
        repository.save(new BatchSearch(Project.project("prj"), "name1", "description1", asSet("q1", "q2"), new Date()));
        repository.save(new BatchSearch(Project.project("prj"), "name2", "description2", asSet("q3", "q4"), new Date()));

        assertThat(repository.getQueued()).hasSize(2);
    }

    @Test
    public void test_get_queued_searches_without_running_state() {
        repository.save(new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.RUNNING, User.local()));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_search_by_id() {
        BatchSearch expected = new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.RUNNING, User.local());
        repository.save(expected);

        BatchSearch actual = repository.get("uuid");

        assertThat(actual).isNotNull();
        assertThat(actual).isEqualTo(expected);
    }

    @Test(expected = JooqBatchSearchRepository.BatchNotFoundException.class)
    public void test_get_search_by_id_not_found() {
        repository.get("uuid");
    }

    @Test
    public void test_get_queued_searches_without_success_state() {
        repository.save(new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.SUCCESS, User.local()));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_failure_state() {
        repository.save(new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_set_state_unknown_batch_search() {
        assertThat(repository.setState("false_uuid", State.RUNNING)).isFalse();
    }

    @Test
    public void test_set_state() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("q1", "q2"), new Date());
        repository.save(batchSearch);

        assertThat(repository.get(batchSearch.uuid).state).isEqualTo(State.QUEUED);
        assertThat(repository.setState(batchSearch.uuid, State.RUNNING)).isTrue();
        assertThat(repository.get(batchSearch.uuid).state).isEqualTo(State.RUNNING);
    }

    @Test
    public void test_set_error_state() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("q1", "q2"), new Date());
        repository.save(batchSearch);

        SearchException error = new SearchException("q1", new RuntimeException("root exception"));
        repository.setState(batchSearch.uuid, error);

        assertThat(repository.get(User.local(), batchSearch.uuid).state).isEqualTo(State.FAILURE);
        assertThat(repository.get(User.local(), batchSearch.uuid).errorMessage).isEqualTo(error.toString());
        assertThat(repository.get(User.local(), batchSearch.uuid).errorQuery).isEqualTo("q1");
    }

    @Test
    public void test_save_results() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("my query", "my other query"), User.local());
        repository.save(batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()))).isTrue();
        assertThat(repository.get(User.local(), batchSearch.uuid).nbResults).isEqualTo(2);
        assertThat(repository.get(User.local(), batchSearch.uuid).queries).includes(entry("my query", 2), entry("my other query", 0));

        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId).isEqualTo("doc1");
        assertThat(results.get(0).documentPath.toString()).isEqualTo("/path/to/doc1");
        assertThat(results.get(1).documentId).isEqualTo("doc2");
        assertThat(results.get(1).documentPath.toString()).isEqualTo("/path/to/doc2");
    }

    @Test
    public void test_save_results_multiple_times() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("my query", "my other query"), User.local());
        repository.save(batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()))).isTrue();
        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc3").build(), createDoc("doc4").build()))).isTrue();

        assertThat(repository.get(User.local(), batchSearch.uuid).nbResults).isEqualTo(4);
        assertThat(repository.get(User.local(), batchSearch.uuid).queries).includes(entry("my query", 4), entry("my other query", 0));
    }

    @Test
    public void test_results_by_query_are_isolated() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name1", "description1", asSet("my query", "my other query"), User.local());
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name2", "description2", asSet("my query", "3rd query"), User.local());
        repository.save(batchSearch1);
        repository.save(batchSearch2);
        repository.saveResults(batchSearch1.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()));

        assertThat(repository.get(User.local(), batchSearch2.uuid).queries).includes(entry("my query", 0), entry("3rd query", 0));
    }

    @Test
    public void test_get_results_paginated() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("query"), User.local());
        repository.save(batchSearch);

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
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("q1", "q2"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("doc1").build(), createDoc("doc2").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("doc3").build(), createDoc("doc4").build()));

        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, null, null,"*","all", singletonList("q1")));
        assertThat(results).hasSize(2);
        assertThat(results).containsExactly(
                resultFrom(createDoc("doc1").build(), 1, "q1"),
                resultFrom(createDoc("doc2").build(), 2, "q1")
        );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, null, null,"*","all", singletonList("q2")))).
                hasSize(2);
    }

    @Test
    public void test_get_results_order() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("q1", "q2"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("a").build(), createDoc("c").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("b").build(), createDoc("d").build()));

        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0))).
                containsExactly(
                        resultFrom(createDoc("a").build(), 1, "q1"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("d").build(), 2, "q2")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, "doc_path", "asc","*","all", null))).
                containsExactly(
                        resultFrom(createDoc("a").build(), 1, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("d").build(), 2, "q2")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, "doc_path", "desc","*","all", null))).
                containsExactly(
                        resultFrom(createDoc("d").build(), 2, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("a").build(), 1, "q1")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, new BatchSearchRepository.WebQuery(0, 0, "doc_nb", "desc","*","all", null))).
                containsExactly(
                        resultFrom(createDoc("d").build(), 2, "q2"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("a").build(), 1, "q1")
                );
    }

    @Test
    public void test_get_batch_search_by_uuid() {
        BatchSearch search = new BatchSearch(Project.project("prj"), "name1", "description1", asSet("q1", "q2"), User.local());
        repository.save(search);

        BatchSearch batchSearch = repository.get(User.local(), search.uuid);

        assertThat(batchSearch).isNotNull();
        assertThat(batchSearch.queries).hasSize(2);
    }

    @Test
    public void test_delete_batch_searches() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name", "description1", asSet("q1", "q2"), User.local());
        repository.save(batchSearch1);
        BatchSearch batchSearch2 = new BatchSearch(Project.project("prj"), "name", "description3", asSet("q3", "q4"), new User("foo"));
        repository.save(batchSearch2);
        repository.saveResults(batchSearch1.uuid, "q2", asList(
                        createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.deleteAll(User.local())).isTrue();
        assertThat(repository.deleteAll(User.local())).isFalse();

        assertThat(repository.get(batchSearch2.uuid)).isNotNull();
        assertThat(repository.getResults(User.local(), batchSearch1.uuid)).hasSize(0);
    }

    @Test
    public void test_delete_batch_search() {
        BatchSearch batchSearch1 = new BatchSearch(Project.project("prj"), "name", "description1", asSet("q1", "q2"), User.local());
        repository.save(batchSearch1);
        repository.saveResults(batchSearch1.uuid, "q2", asList(
                        createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.delete(User.local(), batchSearch1.uuid)).isTrue();
        assertThat(repository.delete(User.local(), batchSearch1.uuid)).isFalse();

        assertThat(repository.getResults(User.local(), batchSearch1.uuid)).hasSize(0);
    }

    @Test
    public void test_delete_batch_search_by_another_user() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "foo search", "description1", asSet("q3", "q4"), new User("foo"));
        User foo = new User("foo");
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q2", asList(
                        createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.delete(User.local(), batchSearch.uuid)).isFalse();

        assertThat(repository.get(batchSearch.uuid)).isNotNull();
        assertThat(repository.getResults(foo, batchSearch.uuid)).hasSize(4);
    }

    @Test(expected = JooqBatchSearchRepository.UnauthorizedUserException.class)
    public void test_get_results_with_bad_user() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("query"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "query", singletonList(createDoc("doc").build()));

        repository.getResults(new User("hacker"), batchSearch.uuid);
    }

    @Test
    public void test_get_results_published_from_another_user() {
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "description", asSet("query"), User.local(), true);
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "query", asList(
              createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.getResults(new User("other"), batchSearch.uuid, new BatchSearchRepository.WebQuery(2, 0))).hasSize(2);
    }

    @Test
    public void test_publish() {
        repository.save(new BatchSearch("uuid", Project.project("prj"), "name", "description",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.get(User.local(), "uuid").published).isFalse();
        assertThat(repository.publish(User.local(), "uuid", true)).isTrue();
        assertThat(repository.get(User.local(), "uuid").published).isTrue();
    }

    @Test
    public void test_publish_unauthorized_user_does_nothing() {
        repository.save(new BatchSearch("uuid", Project.project("prj"), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.publish(new User("unauthorized"), "uuid", true)).isFalse();
    }

    private SearchResult resultFrom(Document doc, int docNb, String queryName) {
        return new SearchResult(queryName, doc.getId(), doc.getRootDocument(), doc.getPath(), doc.getCreationDate(), doc.getContentType(), doc.getContentLength(), docNb);
    }
}
