package org.icij.datashare.db;

import org.icij.datashare.batch.*;
import org.icij.datashare.batch.BatchSearchRecord.State;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.jooq.exception.DataAccessException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;

@RunWith(Parameterized.class)
public class JooqBatchSearchRepositoryTest {
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-08-04T10:20:30Z");
    @Rule public DbSetupRule dbRule;
    @Rule public TemporaryFolder dataFolder = new TemporaryFolder();
    private final JooqBatchSearchRepository repository;

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {new DbSetupRule("jdbc:sqlite:file:memorydb.db?mode=memory&cache=shared")},
                {new DbSetupRule("jdbc:postgresql://postgres/dstest?user=dstest&password=test")}
        });
    }

    public JooqBatchSearchRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createBatchSearchRepository();
    }

    @Test
    public void test_save_and_get_batch_search() {
        BatchSearch batchSearch = new BatchSearch(asList(project("prj1"), project("prj2")), "name1", "description1",
                asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), singletonList("tag"),
                asList("/path/to/docs", "/path/to/pdfs"), 3,true);

        repository.save(batchSearch);

        BatchSearch batchSearchFromGet = repository.get(batchSearch.uuid);

        assertThat(batchSearchFromGet.name).isEqualTo(batchSearch.name);
        assertThat(batchSearchFromGet.published).isEqualTo(batchSearch.published);
        assertThat(batchSearchFromGet.fileTypes).isEqualTo(batchSearch.fileTypes);
        assertThat(batchSearchFromGet.tags).isEqualTo(batchSearch.tags);
        assertThat(batchSearchFromGet.paths).isEqualTo(batchSearch.paths);
        assertThat(batchSearchFromGet.fuzziness).isEqualTo(batchSearch.fuzziness);
        assertThat(batchSearchFromGet.description).isEqualTo(batchSearch.description);
        assertThat(batchSearchFromGet.nbResults).isEqualTo(batchSearch.nbResults);
        assertThat(batchSearchFromGet.phraseMatches).isEqualTo(batchSearch.phraseMatches);
        assertThat(batchSearchFromGet.queries).isEqualTo(batchSearch.queries);
        assertThat(batchSearchFromGet.projects).isEqualTo(batchSearch.projects);
        assertThat(batchSearchFromGet.user).isEqualTo(User.local());
    }

    @Test
    public void test_get_batch_search_by_user_with_projects_without_queries() {
        BatchSearch batchSearch = new BatchSearch(asList(project("prj1"), project("prj2")), "name1", "description1",
                asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), singletonList("tag"),
                asList("/path/to/docs", "/path/to/pdfs"), 3,true);

        repository.save(batchSearch);
        BatchSearch batchSearchFromGet = repository.get(User.local(), batchSearch.uuid, false);

        assertThat(batchSearchFromGet.projects).isEqualTo(batchSearch.projects);
    }

    @Test
    public void test_get_batch_search_by_user_with_projects_with_queries() {
        BatchSearch batchSearch = new BatchSearch(asList(project("prj1"), project("prj2")), "name1", "description1",
                asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), singletonList("tag"),
                asList("/path/to/docs", "/path/to/pdfs"), 3,true);

        repository.save(batchSearch);
        BatchSearch batchSearchFromGet = repository.get(User.local(), batchSearch.uuid, true);

        assertThat(batchSearchFromGet.projects).isEqualTo(batchSearch.projects);
    }

    @Test
    public void test_get_records_filter_by_project() {
        BatchSearch batchSearch1 = new BatchSearch(singletonList(project("prj1")), "name1", "description1",
                asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), singletonList("tag"),
                asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(singletonList(project("prj2")), "name2", "description2",
                asSet("q3", "q4"), new Date(new Date().getTime() + 1000000000));

        repository.save(batchSearch1);
        repository.save(batchSearch2);

        assertThat(repository.getRecords(User.local(), singletonList("prj1")).get(0).uuid).isEqualTo(batchSearch1.uuid);
        assertThat(repository.getRecords(User.local(), singletonList("prj2")).get(0).uuid).isEqualTo(batchSearch2.uuid);
    }

    @Test
    public void test_get_total_filter_by_project() {
        BatchSearch batchSearch1 = new BatchSearch(asList(project("prj1"), project("prj2")), "name1", "description1",
                        asSet("q1", "q2"), User.local(), true, asList("application/json", "image/jpeg"), singletonList("tag"), 
                asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(singletonList(project("prj2")), "name2", "description2",
                asSet("q3", "q4"), User.local(), true);

        repository.save(batchSearch1);
        repository.save(batchSearch2);
        assertThat(repository.getTotal(User.local(), asList("prj1", "prj2"), WebQueryBuilder.createWebQuery().queryAll().build())).isEqualTo(2);
        assertThat(repository.getTotal(User.local(), asList("prj1", "prj2", "prj3"), WebQueryBuilder.createWebQuery().queryAll().build())).isEqualTo(2);
        assertThat(repository.getTotal(User.local(), singletonList("prj2"),  WebQueryBuilder.createWebQuery().queryAll().build())).isEqualTo(1);
        assertThat(repository.getTotal(User.local(), asList("prj2", "prj3"), WebQueryBuilder.createWebQuery().queryAll().build())).isEqualTo(1);
        assertThat(repository.getTotal(User.local(), singletonList("prj3"),  WebQueryBuilder.createWebQuery().queryAll().build())).isEqualTo(0);
        assertThat(repository.getTotal(User.local(), singletonList("prj1"),  WebQueryBuilder.createWebQuery().queryAll().build())).isEqualTo(0);
    }

    @Test
    public void test_get_records() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")),"name","description",asSet("q1", "q2"), User.local(),
                true,asList("application/json", "image/jpeg"), singletonList("tag"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        repository.save(batchSearch);

        List<BatchSearchRecord> batchSearchRecords = repository.getRecords(User.local(), singletonList("prj"));
        assertThat(batchSearchRecords).hasSize(1);
        BatchSearchRecord actual = batchSearchRecords.get(0);
        assertThat(actual.getNbQueries()).isEqualTo(2);
        assertThat(actual.name).isEqualTo(batchSearch.name);
        assertThat(actual.description).isEqualTo(batchSearch.description);
        assertThat(actual.projects).isEqualTo(batchSearch.projects);
        assertThat(actual.user).isEqualTo(batchSearch.user);
        assertThat(actual.published).isEqualTo(batchSearch.published);
        assertThat(actual.date).isEqualTo(batchSearch.date);
        assertThat(actual.state).isEqualTo(batchSearch.state);
        assertThat(actual.nbResults).isEqualTo(batchSearch.nbResults);
        assertThat(actual.errorMessage).isEqualTo(batchSearch.errorMessage);
    }

    @Test
    public void test_get_records_with_multiple_project_intersection() {
        BatchSearch batchSearch = new BatchSearch(asList(project("prj1"), project("prj2")),"name","description",asSet("q1", "q2"), User.local(),
                true,asList("application/json", "image/jpeg"), singletonList("tag"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        repository.save(batchSearch);

        assertThat(repository.getRecords(User.local(), asList("prj1", "prj2"))).isNotEmpty();
        assertThat(repository.getRecords(User.local(), asList("prj1", "prj3"))).isEmpty();
        assertThat(repository.getRecords(User.local(), singletonList("prj1"))).isEmpty();
        assertThat(repository.getRecords(User.local(), asList("prj1", "prj2", "prj3"))).isNotEmpty();
    }

    @Test
    public void test_get_records_with_query_and_field() {
        BatchSearch batchSearch1 = new BatchSearch(singletonList(project("prj")),"foo","baz",asSet("q1", "q2"), User.local(),
                false,asList("application/json", "image/jpeg"), singletonList("tag"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(singletonList(project("anotherPrj")),"bar","baz",asSet("q3", "q2"), User.local(),
                true,asList("application/json", "image/jpeg"), null, asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch3 = new BatchSearch(singletonList(project("otherPrj")),"bar","baz",asSet("q4", "q1"), User.local(),
                true,asList("application/json", "image/jpeg"), null, asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        long currentDateTime = new Date().getTime();
        BatchSearch batchSearch4 = new BatchSearch(asList(project("prj"),project("anotherPrj")),"qux","baz",asSet("q1", "q3"), new Date(currentDateTime + 50), State.SUCCESS, true);
        repository.save(batchSearch1);
        repository.save(batchSearch2);
        repository.save(batchSearch3);
        repository.save(batchSearch4);

        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery("*","all").build())).hasSize(4);
        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery("foo","all").build())).hasSize(1);
        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery("oo","all").build())).hasSize(1);
        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery("baz","description").build())).hasSize(4);
        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery("baz","name").build())).hasSize(0);
        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery("","all").build()));

        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery().queryAll().withProjects(singletonList("anotherPrj")).build())).hasSize(1);
        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery().queryAll().withProjects(asList("prj","anotherPrj")).withState(asList(State.SUCCESS.toString(), State.QUEUED.toString())).build())).hasSize(3);

        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery().queryAll().withBatchDate(asList(String.valueOf(currentDateTime + 10), String.valueOf(currentDateTime + 100))).build())).hasSize(1);

        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery().queryAll().withState(singletonList(State.SUCCESS.toString())).build())).hasSize(1);
        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery().queryAll().withState(asList(State.SUCCESS.toString(), State.QUEUED.toString())).build())).hasSize(4);

        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery().queryAll().withProjects(asList("otherPrj","prj")).withPublishState("0").build())).hasSize(1);

        assertThat(repository.getRecords(User.local(), asList("prj", "otherPrj", "anotherPrj"), WebQueryBuilder.createWebQuery().queryAll().withProjects(asList("otherPrj","prj")).withState(asList(State.SUCCESS.toString(), State.QUEUED.toString())).build())).hasSize(2);
    }

    @Test
    public void test_get_records_with_query() {
        IntStream.range(0, 5).mapToObj(i -> new BatchSearch(singletonList(project("prj1")), "name" + i, "description" + i, asSet("q1/" + i, "q2/" + i), User.local(),
                true, asList("application/json", "image/jpeg"), singletonList("tag"), asList("/path/to/docs", "/path/to/pdfs"), 3, true)).
                forEach(bs -> {
                            repository.save(bs);
                            DatashareTime.getInstance().addMilliseconds(1000);
                        }
                );

        List<BatchSearchRecord> from0To2 = repository.getRecords(User.local(), asList("prj1", "prj2"), WebQueryBuilder.createWebQuery().queryAll().withRange(0,2).build());
        assertThat(from0To2).hasSize(2);
        assertThat(from0To2.get(0).name).isEqualTo("name4");
        assertThat(from0To2.get(1).name).isEqualTo("name3");

        List<BatchSearchRecord> from0To2OrderByName = repository.getRecords(User.local(), asList("prj1", "prj2"),WebQueryBuilder.createWebQuery().queryAll().withRange(1,1).withSortOrder("name","asc").queryAll().queriesRetrieved(true).build());
        assertThat(from0To2OrderByName).hasSize(1);
        assertThat(from0To2OrderByName.get(0).name).isEqualTo("name1");
    }
    @Test
    public void test_records_filtered_by_content_type(){
        BatchSearch batchSearch1 = new BatchSearch(singletonList(project("prj1")),"foo","baz",asSet("q1", "q2"), User.local(),
                false,asList("application/json", "application/text"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch2 = new BatchSearch(singletonList(project("prj1")),"bar","baz",asSet("q3", "q2"), User.local(),
                true,asList("application/json"), asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        BatchSearch batchSearch3 = new BatchSearch(asList(project("prj1")),"bar","baz",asSet("q3", "q2"), User.local(),
                true,null, asList("/path/to/docs", "/path/to/pdfs"),  3,true);
        repository.save(batchSearch1);
        repository.save(batchSearch2);
        repository.save(batchSearch3);
        List<BatchSearchRecord> all = repository.getRecords(User.local(), asList("prj1"), WebQueryBuilder.createWebQuery().queryAll().withContentTypes(asList("application/json")).build());
        assertThat(all).hasSize(1);
    }

    @Test
    public void test_get_queued_searches() {
        repository.save(new BatchSearch(singletonList(project("prj")), "name1", "description1", asSet("q1", "q2"), new Date()));
        repository.save(new BatchSearch(singletonList(project("prj")), "name2", "description2", asSet("q3", "q4"), new Date()));

        assertThat(repository.getQueued()).hasSize(2);
    }

    @Test
    public void test_get_queued_searches_without_running_state() {

        repository.save(new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.RUNNING, User.local()));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_search_by_id() {
        BatchSearch expected = new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
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
        repository.save(new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.SUCCESS, User.local()));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_get_queued_searches_without_failure_state() {
        repository.save(new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.getQueued()).hasSize(0);
    }

    @Test
    public void test_set_state_unknown_batch_search() {
        assertThat(repository.setState("false_uuid", State.RUNNING)).isFalse();
    }

    @Test
    public void test_set_state() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("q1", "q2"), new Date());
        repository.save(batchSearch);

        assertThat(repository.get(batchSearch.uuid).state).isEqualTo(State.QUEUED);
        assertThat(repository.setState(batchSearch.uuid, State.RUNNING)).isTrue();
        assertThat(repository.get(batchSearch.uuid).state).isEqualTo(State.RUNNING);
    }

    @Test
    public void test_set_error_state() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("q1", "q2"), new Date());
        repository.save(batchSearch);

        SearchException error = new SearchException("q1", new RuntimeException("root exception"));
        repository.setState(batchSearch.uuid, error);

        assertThat(repository.get(User.local(), batchSearch.uuid).state).isEqualTo(State.FAILURE);
        assertThat(repository.get(User.local(), batchSearch.uuid).errorMessage).isEqualTo(error.toString());
        assertThat(repository.get(User.local(), batchSearch.uuid).errorQuery).isEqualTo("q1");
    }

    @Test
    public void test_save_results() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("my query", "my other query"), User.local());
        repository.save(batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()))).isTrue();
        assertThat(repository.get(User.local(), batchSearch.uuid).nbResults).isEqualTo(2);
        assertThat(repository.get(User.local(), batchSearch.uuid).queries).includes(entry("my query", 2), entry("my other query", 0));

        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId).isEqualTo("doc1");
        assertThat(results.get(0).documentPath.toString()).isEqualTo("/path/to/doc1");
        assertThat(results.get(0).project.getId()).isEqualTo("prj");
        assertThat(results.get(1).documentId).isEqualTo("doc2");
        assertThat(results.get(1).documentPath.toString()).isEqualTo("/path/to/doc2");
        assertThat(results.get(1).project.getId()).isEqualTo("prj");
    }

    @Test
    public void test_save_results_multiple_times() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("my query", "my other query"), User.local());
        repository.save(batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()))).isTrue();
        assertThat(repository.saveResults(batchSearch.uuid, "my query", asList(createDoc("doc3").build(), createDoc("doc4").build()))).isTrue();

        assertThat(repository.get(User.local(), batchSearch.uuid).nbResults).isEqualTo(4);
        assertThat(repository.get(User.local(), batchSearch.uuid).queries).includes(entry("my query", 4), entry("my other query", 0));
    }

    @Test
    public void test_results_by_query_are_isolated() {
        BatchSearch batchSearch1 = new BatchSearch(singletonList(project("prj")), "name1", "description1", asSet("my query", "my other query"), User.local());
        BatchSearch batchSearch2 = new BatchSearch(singletonList(project("prj")), "name2", "description2", asSet("my query", "3rd query"), User.local());
        repository.save(batchSearch1);
        repository.save(batchSearch2);
        repository.saveResults(batchSearch1.uuid, "my query", asList(createDoc("doc1").build(), createDoc("doc2").build()));

        assertThat(repository.get(User.local(), batchSearch2.uuid).queries).includes(entry("my query", 0), entry("3rd query", 0));
    }

    @Test
    public void test_get_results_paginated() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("query"), User.local());
        repository.save(batchSearch);

        assertThat(repository.saveResults(batchSearch.uuid, "query", asList(
                createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()))).isTrue();

        assertThat(repository.getResults(User.local(), batchSearch.uuid,WebQueryBuilder.createWebQuery().withRange(0,2).build())).hasSize(2);
        assertThat(repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().withRange(0,2).build())).containsExactly(
                resultFrom(createDoc("doc1").build(), 1, "query"), resultFrom(createDoc("doc2").build(), 2, "query"));
        assertThat(repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().withRange(2,2).build())).containsExactly(
                resultFrom(createDoc("doc3").build(), 3, "query"), resultFrom(createDoc("doc4").build(), 4, "query"));

    }

    @Test
    public void test_get_results_filtered_by_query() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("q1", "q2"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("doc1").build(), createDoc("doc2").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("doc3").build(), createDoc("doc4").build()));
        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withQueries(singletonList("q1")).build());
        assertThat(results).hasSize(2);
        assertThat(results).containsExactly(
                resultFrom(createDoc("doc1").build(), 1, "q1"),
                resultFrom(createDoc("doc2").build(), 2, "q1")
        );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withQueries(singletonList("q2")).build())).
                hasSize(2);
    }

    @Test
    public void test_get_results_filtered_by_excluding_queries() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("q1", "q2"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("doc1").build(), createDoc("doc2").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("doc3").build(), createDoc("doc4").build()));
        repository.saveResults(batchSearch.uuid, "q3", asList(createDoc("doc5").build(), createDoc("doc6").build()));
        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withQueries(singletonList("q1")).build());
        assertThat(results).hasSize(2);
        assertThat(results).containsExactly(
                resultFrom(createDoc("doc1").build(), 1, "q1"),
                resultFrom(createDoc("doc2").build(), 2, "q1")
        );
        List<SearchResult> resultExcludingQ1 = repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withQueries(singletonList("q1")).queriesExcluded(true).build());
        assertThat(resultExcludingQ1).hasSize(4);
        assertThat(resultExcludingQ1).containsExactly(
                resultFrom(createDoc("doc3").build(), 1, "q2"),
                resultFrom(createDoc("doc4").build(), 2, "q2"),
                resultFrom(createDoc("doc5").build(), 1, "q3"),
                resultFrom(createDoc("doc6").build(), 2, "q3")
        );
    }

    @Test
    public void test_get_results_filtered_by_content_type() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("q1", "q2"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("doc1").ofMimeType("application/pdf").build(), createDoc("doc2").ofMimeType("content/type").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("doc3").ofMimeType("application/pdf").build(), createDoc("doc4").build()));
        List<SearchResult> results = repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withContentTypes(asList("application/pdf")).build());
        assertThat(results).hasSize(2);
        assertThat(results).containsExactly(
                resultFrom(createDoc("doc1").ofMimeType("application/pdf").build(), 1, "q1"),
                resultFrom(createDoc("doc3").ofMimeType("application/pdf").build(), 1, "q2")
        );
        //empty list means all and no content types
        List<SearchResult> results2 = repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withContentTypes(asList()).build());
        assertThat(results2).hasSize(4);

    }
    @Test
    public void test_get_results_order() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("q1", "q2"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q1", asList(createDoc("a").build(), createDoc("c").build()));
        repository.saveResults(batchSearch.uuid, "q2", asList(createDoc("b").build(), createDoc("d").build()));

        assertThat(repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().build())).
                containsExactly(
                        resultFrom(createDoc("a").build(), 1, "q1"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("d").build(), 2, "q2")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withSortOrder("doc_path","asc").build())).
                containsExactly(
                        resultFrom(createDoc("a").build(), 1, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("d").build(), 2, "q2")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withSortOrder("doc_path","desc").build())).
                containsExactly(
                        resultFrom(createDoc("d").build(), 2, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("a").build(), 1, "q1")
                );
        assertThat(repository.getResults(User.local(), batchSearch.uuid, WebQueryBuilder.createWebQuery().queryAll().withSortOrder("doc_nb","desc").build())).
                containsExactly(
                        resultFrom(createDoc("d").build(), 2, "q2"),
                        resultFrom(createDoc("b").build(), 1, "q2"),
                        resultFrom(createDoc("c").build(), 2, "q1"),
                        resultFrom(createDoc("a").build(), 1, "q1")
                );
    }

    @Test
    public void test_get_batch_search_queries_order(){
        LinkedHashSet<String> queryList = new LinkedHashSet<String>() {{
            add("q4");
            add("q3");
            add("q2");
        }};
        BatchSearch batchSearch = new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                queryList, new Date(), State.RUNNING, User.local());
        repository.save(batchSearch);
        Map<String, Integer> queriesNaturalOrder = repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 0, null, null);

        Entry<String, Integer> entry = GetEntry(queriesNaturalOrder,0);
        assertThat(entry.getKey()).isEqualTo("q4");
        assertThat(entry.getValue()).isEqualTo(0);

        entry = GetEntry(queriesNaturalOrder,1);
        assertThat(entry.getKey()).isEqualTo("q3");
        assertThat(entry.getValue()).isEqualTo(0);

        entry = GetEntry(queriesNaturalOrder,2);
        assertThat(entry.getKey()).isEqualTo("q2");
        assertThat(entry.getValue()).isEqualTo(0);

        Map<String, Integer> queries = repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 1, null, null);
        entry = GetEntry(queries,0);
        assertThat(entry.getValue()).isEqualTo(0);
        assertThat(entry.getKey()).isEqualTo("q4");

        queries = repository.getQueries(batchSearch.user, batchSearch.uuid, 1, 1, null, null);
        entry = GetEntry(queries,0);
        assertThat(entry.getValue()).isEqualTo(0);
        assertThat(entry.getKey()).isEqualTo("q3");
    }

    @Test
    public void test_get_batch_search_by_uuid_with_queries() {
        BatchSearch search = new BatchSearch(singletonList(project("prj")), "name1", "description1", asSet("q1", "q2"), User.local());
        repository.save(search);

        BatchSearch batchSearch = repository.get(User.local(), search.uuid);

        assertThat(batchSearch).isNotNull();
        assertThat(batchSearch.queries).hasSize(2);

        batchSearch = repository.get(User.local(), search.uuid, true);

        assertThat(batchSearch).isNotNull();
        assertThat(batchSearch.queries).hasSize(2);
    }

    @Test
    public void test_get_batch_search_by_uuid_without_queries() {
        BatchSearch search = new BatchSearch(singletonList(project("prj")), "name1", "description1", asSet("q1", "q2"), User.local());
        repository.save(search);
        BatchSearch batchSearch = repository.get(User.local(), search.uuid, false);
        assertThat(batchSearch).isNotNull();
        assertThat(batchSearch.queries).hasSize(0);
        assertThat(batchSearch.getNbQueries()).isEqualTo(2);
    }

    @Test
    public void test_delete_batch_searches() {
        BatchSearch batchSearch1 = new BatchSearch(singletonList(project("prj")), "name", "description1", asSet("q1", "q2"), User.local());
        repository.save(batchSearch1);
        BatchSearch batchSearch2 = new BatchSearch(singletonList(project("prj")), "name", "description3", asSet("q3", "q4"), new User("foo"));
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
        BatchSearch batchSearch1 = new BatchSearch(singletonList(project("prj")), "name", "description1", asSet("q1", "q2"), User.local());
        repository.save(batchSearch1);
        repository.saveResults(batchSearch1.uuid, "q2", asList(
                        createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.delete(User.local(), batchSearch1.uuid)).isTrue();
        assertThat(repository.delete(User.local(), batchSearch1.uuid)).isFalse();

        assertThat(repository.getResults(User.local(), batchSearch1.uuid)).hasSize(0);
    }

    @Test
    public void test_delete_batch_search_by_another_user() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "foo search", "description1", asSet("q3", "q4"), new User("foo"));
        User foo = new User("foo");
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q2", asList(
                        createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.delete(User.local(), batchSearch.uuid)).isFalse();

        assertThat(repository.get(batchSearch.uuid)).isNotNull();
        assertThat(repository.getResults(foo, batchSearch.uuid)).hasSize(4);
    }

    @Test
    public void test_delete_batch_search_running_do_not_remove_row() {
        for (State s: State.values()) {
            repository.save(new BatchSearch("uuid_" + s, singletonList(project("prj")), s.name(), "description", asSet("q1", "q2"), new Date(), s, User.local()));
        }

        assertThat(repository.delete(User.local(), "uuid_QUEUED")).isTrue();
        assertThat(repository.delete(User.local(), "uuid_RUNNING")).isFalse();
        assertThat(repository.delete(User.local(), "uuid_FAILURE")).isTrue();
        assertThat(repository.delete(User.local(), "uuid_SUCCESS")).isTrue();
    }

    @Test(expected = JooqBatchSearchRepository.UnauthorizedUserException.class)
    public void test_get_results_with_bad_user() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("query"), User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "query", singletonList(createDoc("doc").build()));

        repository.getResults(new User("hacker"), batchSearch.uuid);
    }

    @Test
    public void test_get_results_published_from_another_user() {
        BatchSearch batchSearch = new BatchSearch(singletonList(project("prj")), "name", "description", asSet("query"), User.local(), true);
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "query", asList(
              createDoc("doc1").build(), createDoc("doc2").build(), createDoc("doc3").build(), createDoc("doc4").build()));

        assertThat(repository.getResults(new User("other"), batchSearch.uuid,WebQueryBuilder.createWebQuery().withRange(0,2).build())).hasSize(2);
    }

    @Test
    public void test_publish() {
        repository.save(new BatchSearch("uuid", singletonList(project("prj")), "name", "description",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.get(User.local(), "uuid").published).isFalse();
        assertThat(repository.publish(User.local(), "uuid", true)).isTrue();
        assertThat(repository.get(User.local(), "uuid").published).isTrue();
    }

    @Test
    public void test_publish_unauthorized_user_does_nothing() {
        repository.save(new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.FAILURE, User.local()));

        assertThat(repository.publish(new User("unauthorized"), "uuid", true)).isFalse();
    }

    @Test
    public void test_reset_batch_search() {
        BatchSearch batchSearch = new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                asSet("q1", "q2"), new Date(), State.RUNNING, User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "query",asList(createDoc("doc1").build(),createDoc("doc2").build()));

        assertThat(repository.reset(batchSearch.uuid)).isTrue();
        assertThat(repository.get(batchSearch.uuid).state).isEqualTo(State.QUEUED);
        assertThat(repository.getResults(User.local(), batchSearch.uuid)).hasSize(0);
    }

    @Test
    public void test_get_batch_search_queries() {
        List<Project> project = singletonList(project("prj"));
        LinkedHashSet<String> bsQueries = new LinkedHashSet<String>() {{
            add("q2");
            add("q1");
        }};
        BatchSearch batchSearch = new BatchSearch("uuid", project, "name1", "description1", bsQueries, new Date(), State.RUNNING, User.local());
        repository.save(batchSearch);
        Map<String, Integer> queries = repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 2, null, null);

        assertThat(queries).isNotNull();
        assertThat(queries).hasSize(2);
        Iterator<Entry<String, Integer>> entrySetIterator = queries.entrySet().iterator();
        assertThat(entrySetIterator.next()).isEqualTo(new AbstractMap.SimpleEntry<>("q2", 0));
        assertThat(entrySetIterator.next()).isEqualTo(new AbstractMap.SimpleEntry<>("q1", 0));

        assertThat(repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 1, null, null)).hasSize(1);
        assertThat(repository.getQueries(batchSearch.user, batchSearch.uuid, 1, 2, null, null)).hasSize(1);
        assertThat(repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 0, null, null)).hasSize(2);
    }

    @Test
    public void test_get_batch_search_queries_with_zero_results() {
        List<Project> project = singletonList(project("prj"));
        LinkedHashSet<String> bsQueries = new LinkedHashSet<String>() {{
            add("q1");
            add("q2");
            add("q3");
        }};
        BatchSearch batchSearch = new BatchSearch("uuid", project, "name1", "description1", bsQueries, new Date(), State.RUNNING, User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q2", List.of(createDoc("doc1").build()));
        repository.saveResults(batchSearch.uuid, "q3", List.of(createDoc("doc1").build()));
        Map<String, Integer> queries = repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 0, null, null, 0);
        assertThat(queries).isNotNull();
        assertThat(queries).hasSize(1);
        Iterator<Entry<String, Integer>> entrySetIterator = queries.entrySet().iterator();
        assertThat(entrySetIterator.next()).isEqualTo(new AbstractMap.SimpleEntry<>("q1", 0));
    }

    @Test
    public void test_get_batch_search_queries_with_two_as_max_results() {
        List<Project> project = singletonList(project("foo"));
        LinkedHashSet<String> bsQueries = new LinkedHashSet<String>() {{
            add("q1");
            add("q2");
        }};
        BatchSearch batchSearch = new BatchSearch("bar", project, "name1", "description1", bsQueries, new Date(), State.RUNNING, User.local());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q2", List.of(createDoc("doc1").build()));
        repository.saveResults(batchSearch.uuid, "q2", List.of(createDoc("doc2").build()));
        repository.saveResults(batchSearch.uuid, "q2", List.of(createDoc("doc3").build()));
        Map<String, Integer> queries = repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 2, null, null, 1);
        assertThat(queries).isNotNull();
        assertThat(queries).hasSize(1);
        Iterator<Entry<String, Integer>> entrySetIterator = queries.entrySet().iterator();
        assertThat(entrySetIterator.next()).isEqualTo(new AbstractMap.SimpleEntry<>("q1", 0));
    }

    @Test
    public void test_get_batch_search_queries_with_no_max_results() {
        List<Project> project = singletonList(project("prj"));
        LinkedHashSet<String> bsQueries = new LinkedHashSet<String>() {{
            add("q1");
            add("q2");
        }};
        BatchSearch batchSearch = new BatchSearch("uuid", project, "name1", "description1", bsQueries, new Date(), State.RUNNING, User.local());
        List<Document> matchingDocuments = List.of(createDoc("doc1").build());
        repository.save(batchSearch);
        repository.saveResults(batchSearch.uuid, "q2", matchingDocuments);
        Map<String, Integer> queries = repository.getQueries(batchSearch.user, batchSearch.uuid, 0, 2, null, null, -1);
        assertThat(queries).isNotNull();
        assertThat(queries).hasSize(2);
        Iterator<Entry<String, Integer>> entrySetIterator = queries.entrySet().iterator();
        assertThat(entrySetIterator.next()).isEqualTo(new AbstractMap.SimpleEntry<>("q1", 0));
        assertThat(entrySetIterator.next()).isEqualTo(new AbstractMap.SimpleEntry<>("q2", 1));
    }

    static public Entry<String, Integer> GetEntry(Map<String,Integer> queries, int position){
        Iterator<Entry<String, Integer>> iterator = queries.entrySet().iterator();
        int i = 0;
        Entry<String, Integer> entry = iterator.next();
        while (i++<position && iterator.hasNext()){
            entry = iterator.next();
        }
        return entry;
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_get_batch_search_queries_with_negative_from_is_illegal() {
       repository.getQueries(User.local(), "uuid", -1, 2, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_get_batch_search_queries_with_negative_size_is_illegal() {
        repository.getQueries(User.local(), "uuid", 2, -1, null, null);
    }

    @Test
    public void test_get_batch_search_queries_with_search_filter() {
        BatchSearch batchSearch = new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                new LinkedHashSet<String>() {{add("query abc");add("def query");}}, new Date(), State.RUNNING, User.local());
        repository.save(batchSearch);
        assertThat(repository.getQueries(User.local(), "uuid", 0, 0, "query", null)).hasSize(2);
        assertThat(repository.getQueries(User.local(), "uuid", 0, 0, "def", null)).hasSize(1);
        assertThat(repository.getQueries(User.local(), "uuid", 0, 0, "abc", null)).hasSize(1);
    }

    @Test
    public void test_get_batch_search_queries_with_orderBy_param() {
        BatchSearch batchSearch = new BatchSearch("uuid", singletonList(project("prj")), "name1", "description1",
                new LinkedHashSet<String>() {{add("q2"); add("q1");}}, new Date(), State.RUNNING, User.local());
        repository.save(batchSearch);

        Map<String, Integer> queries = repository.getQueries(User.local(), "uuid", 0, 0, null, "query");
        assertThat(queries.entrySet().iterator().next().getKey()).isEqualTo("q1");

        queries = repository.getQueries(User.local(), "uuid", 0, 0, null, "query_number");
        assertThat(queries.entrySet().iterator().next().getKey()).isEqualTo("q2");

    }

    @Test(expected = DataAccessException.class)
    public void test_get_batch_search_queries_with_unknown_orderBy_field() {
        repository.getQueries(User.local(), "uuid", 0, 0, null, "unknown_field");
    }

    private SearchResult resultFrom(Document doc, int docNb, String queryName) {
        return new SearchResult(queryName, doc.getId(), doc.getRootDocument(), doc.getPath(), doc.getCreationDate(), doc.getContentType(), doc.getContentLength(), docNb);
    }
}
