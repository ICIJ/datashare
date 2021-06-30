package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.IOException;
import java.util.Date;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchSearchRunnerIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");

    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);
    @Mock BatchSearchRepository repository;
    @After public void tearDown() throws IOException { es.removeAll();}

    @Test
    public void test_search_with_file_types() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("mydoc"), User.local(), false, singletonList("application/pdf"), null, 0);
        BatchSearch searchOk = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("mydoc"), User.local(), false, singletonList("text/plain"), null, 0);
        returnBatchSearches(asList(searchKo, searchOk));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("mydoc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "mydoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_paths() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("mydoc"), User.local(),false, null,
                singletonList("/foo/bar"), 0);
        BatchSearch searchOk = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("mydoc"), User.local(),false, null,
                singletonList("/path/to"), 0);
        returnBatchSearches(asList(searchKo, searchOk));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("mydoc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "mydoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_fuzziness() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo1 = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("doc"), User.local(),false, null,
                null, 1);
        BatchSearch searchKo2 = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("nodoc"), User.local(),false, null,
                null, 1);
        BatchSearch searchOk = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("hedoc"), User.local(),false, null,
                null, 2);
        returnBatchSearches(asList(searchKo1, searchKo2, searchOk));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        verify(repository, never()).saveResults(eq(searchKo1.uuid), eq("doc"), anyList());
        verify(repository, never()).saveResults(eq(searchKo2.uuid), eq("nodoc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "hedoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_phraseMatches() throws Exception {
        Document mydoc = createDoc("docId").with("mydoc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("to find mydoc"), User.local(),false, null,
                null, true);
        BatchSearch searchOk = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("mydoc to find"), User.local(),false, null,
                null,true);
        returnBatchSearches(asList(searchKo, searchOk));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("to find mydoc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "mydoc to find", singletonList(mydoc));
    }

    @Test
    public void test_search_with_phraseMatches_with_ner() throws Exception {
        Document mydoc = createDoc("docId").with("anne's doc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        indexer.add(TEST_INDEX, NamedEntity.create(NamedEntity.Category.PERSON, "anne", asList(12L), mydoc.getId(), mydoc.getRootDocument(), Pipeline.Type.CORENLP, Language.FRENCH));
        BatchSearch searchKo = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("anne doc"), User.local(),false, null,
                null, true);
        BatchSearch searchOk = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("anne's doc"), User.local(),false, null,
                null,true);
        returnBatchSearches(asList(searchKo, searchOk));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("anne doc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "anne's doc", singletonList(mydoc));
    }

    @Test
    public void test_search_phrase_matches_with_slop() throws Exception {
        // with phrase match a permutation (they call it transposition) is 2 slop
        // https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-match-query-phrase.html
        Document mydoc = createDoc("docId").with("mydoc find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("find mydoc"), User.local(), false, null,
                 null, 2,true);
        returnBatchSearches(singletonList(search));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        verify(repository).saveResults(search.uuid, "find mydoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_operators() throws Exception {
        Document mydoc1 = createDoc("docId1").with("mydoc one").build();
        Document mydoc2 = createDoc("docId2").with("mydoc two").build();
        indexer.add(TEST_INDEX, mydoc1);
        indexer.add(TEST_INDEX, mydoc2);
        BatchSearch search = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("mydoc AND one"), User.local());
        returnBatchSearches(singletonList(search));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        verify(repository).saveResults(search.uuid, "mydoc AND one", singletonList(mydoc1));
    }

    @Test
    public void test_search_with_error() throws Exception {
        Document mydoc = createDoc("docId1").with("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("AND mydoc"), User.local());
        returnBatchSearches(singletonList(search));

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).call();

        ArgumentCaptor<SearchException> argument = ArgumentCaptor.forClass(SearchException.class);
        verify(repository).setState(eq(search.uuid), argument.capture());
        assertThat(argument.getValue().toString()).contains("Failed to parse query [AND mydoc]");
    }

    @Test
    public void test_run_with_batchid() throws Exception {
        Document mydoc = createDoc("docId1").with("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(project(TEST_INDEX), "name", "desc", asSet("mydoc"), User.local());
        when(repository.get(search.uuid)).thenReturn(search);

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).run(search.uuid);

        verify(repository).saveResults(search.uuid, "mydoc", singletonList(mydoc));
    }

    @Test
    public void test_run_with_batchid_batch_is_not_queued() throws IOException {
        test_is_not_executed(BatchSearch.State.RUNNING);
        test_is_not_executed(BatchSearch.State.FAILURE);
        test_is_not_executed(BatchSearch.State.SUCCESS);
    }

    private void test_is_not_executed(BatchSearch.State state) throws IOException {
        Document mydoc = createDoc("docId1").with("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch("uuid", project(TEST_INDEX), "name", "desc", asSet("mydoc"), new Date(), state, User.local());
        when(repository.get(search.uuid)).thenReturn(search);

        assertThat(new BatchSearchRunner(indexer, repository, new PropertiesProvider(), local()).run(search.uuid)).isEqualTo(0);

        verify(repository, never()).saveResults(anyString(), any(), any());
    }

    private void returnBatchSearches(List<BatchSearch> ts) {
        when(repository.getQueued()).thenReturn(ts.stream().map(batchSearch -> batchSearch.uuid).collect(toList()));
        ts.forEach(bs -> when(repository.get(bs.uuid)).thenReturn(bs));
    }

    @Before
    public void setUp() { initMocks(this);}
}
