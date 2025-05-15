package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
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
import org.mockito.Mock;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchSearchRunnerIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    @Mock Function<Double, Void> progressCb;
    @Mock BatchSearchRepository repository;
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    @After public void tearDown() throws IOException { es.removeAll();}

    @Test
    public void test_search_with_file_types_ok() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all&f[contentType]=text/plain";
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), uri, User.local(), false, singletonList("text/plain"), null, null, 0);
        when(repository.get(local(), search.uuid)).thenReturn(search);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call();
        verify(repository).saveResults(search.uuid, "mydoc", singletonList(mydoc), true);
    }

    private Task taskView(BatchSearch search) {
        return new Task(search.uuid, BatchSearchRunner.class.getName(), User.local());
    }

    @Test
    public void test_search_with_file_types_ko() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        String uri = "/?q=&from=0&size=25&sort=relevance&indices=test&field=all&f[contentType]=application/pdf";
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), uri, User.local(), false, singletonList("application/pdf"), null,null, 0);
        when(repository.get(local(), searchKo.uuid)).thenReturn(searchKo);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchKo), progressCb).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("mydoc"), anyList(), anyBoolean());
    }

    @Test
    public void test_search_with_paths_ok() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), null, User.local(),false, null, null,
                singletonList("/path/to"), 0);
        when(repository.get(local(), searchOk.uuid)).thenReturn(searchOk);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchOk), progressCb).call();

        verify(repository).saveResults(searchOk.uuid, "mydoc", singletonList(mydoc), true);
    }

    @Test
    public void test_search_with_paths_ko() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), null, User.local(),false, null, null,
                singletonList("/foo/bar"), 0);
        when(repository.get(local(), searchKo.uuid)).thenReturn(searchKo);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchKo), progressCb).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("mydoc"), anyList(), anyBoolean());
    }

    @Test
    public void test_search_with_fuzziness() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo1 = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("doc"), null, User.local(),false, null, null,
                null, 1);
        BatchSearch searchKo2 = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("nodoc"), null, User.local(),false, null, null,
                null, 1);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("hedoc"), null, User.local(),false, null, null,
                null, 2);
        when(repository.get(local(), searchKo1.uuid)).thenReturn(searchKo1);
        when(repository.get(local(), searchKo2.uuid)).thenReturn(searchKo2);
        when(repository.get(local(), searchOk.uuid)).thenReturn(searchOk);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchKo1), progressCb).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchKo2), progressCb).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchOk), progressCb).call();

        verify(repository, never()).saveResults(eq(searchKo1.uuid), eq("doc"), anyList());
        verify(repository, never()).saveResults(eq(searchKo2.uuid), eq("nodoc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "hedoc", singletonList(mydoc), true);
    }

    @Test
    public void test_search_with_phraseMatches() throws Exception {
        Document mydoc = createDoc("docId").with("mydoc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("to find mydoc"), null, User.local(),false, null, null,
                null, true);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc to find"), null, User.local(),false, null, null,
                null,true);
        when(repository.get(local(), searchKo.uuid)).thenReturn(searchKo);
        when(repository.get(local(), searchOk.uuid)).thenReturn(searchOk);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchKo), progressCb).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchOk), progressCb).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("to find mydoc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "mydoc to find", singletonList(mydoc), true);
    }

    @Test
    public void test_search_with_phraseMatches_with_ner() throws Exception {
        Document mydoc = createDoc("docId").with("anne's doc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        indexer.add(TEST_INDEX, NamedEntity.create(NamedEntity.Category.PERSON, "anne", List.of(12L), mydoc.getId(), mydoc.getRootDocument(), Pipeline.Type.CORENLP, Language.FRENCH));
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("anne doc"), null, User.local(),false, null, null,
                null, true);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("anne's doc"), null, User.local(),false, null, null,
                null,true);
        when(repository.get(local(), searchOk.uuid)).thenReturn(searchOk);
        when(repository.get(local(), searchKo.uuid)).thenReturn(searchKo);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchKo), progressCb).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchOk), progressCb).call();

        verify(repository, never()).saveResults(eq(searchKo.uuid), eq("anne doc"), anyList());
        verify(repository).saveResults(searchOk.uuid, "anne's doc", singletonList(mydoc), true);
    }

    @Test
    public void test_search_phrase_matches_with_slop() throws Exception {
        // with phrase match a permutation (they call it transposition) is 2 slop
        // https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-match-query-phrase.html
        Document mydoc = createDoc("docId").with("mydoc find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("find mydoc"), null, User.local(), false, null, null,
                 null, 2,true);
        when(repository.get(local(), search.uuid)).thenReturn(search);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call();

        verify(repository).saveResults(search.uuid, "find mydoc", singletonList(mydoc), true);
    }

    @Test
    public void test_search_with_operators() throws Exception {
        Document mydoc1 = createDoc("docId1").with("mydoc one").build();
        Document mydoc2 = createDoc("docId2").with("mydoc two").build();
        indexer.add(TEST_INDEX, mydoc1);
        indexer.add(TEST_INDEX, mydoc2);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc AND one"), null, User.local());
        when(repository.get(local(), search.uuid)).thenReturn(search);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call();

        verify(repository).saveResults(search.uuid, "mydoc AND one", singletonList(mydoc1), true);
    }

    @Test
    public void test_search_with_query_template() throws Exception {
        String queryBody = "{\"bool\":{\"must\":[{\"match_all\":{}},{\"bool\":{\"should\":[{\"query_string\":{\"query\":\"<query>\"}}]}},{\"match\":{\"type\":\"Document\"}}]}}";
        Document mydoc = createDoc("docId").with("mydoc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), null, User.local(),false, null, queryBody,
                null, 0);
        when(repository.get(local(), searchOk.uuid)).thenReturn(searchOk);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchOk), progressCb).call();

        verify(repository).saveResults(searchOk.uuid, "mydoc", singletonList(mydoc), true);
    }

    @Test
    public void test_search_without_query_template() throws Exception {
        Document mydoc = createDoc("docId").with("mydoc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), null, User.local(),false, null, null,
                null, 0);
        when(repository.get(local(), searchOk.uuid)).thenReturn(searchOk);

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(searchOk), progressCb).call();

        verify(repository).saveResults(searchOk.uuid, "mydoc", singletonList(mydoc), true);
    }

    @Test
    public void test_search_with_error() throws Exception {
        Document mydoc = createDoc("docId1").with("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("AND mydoc"), null, User.local());
        when(repository.get(local(), search.uuid)).thenReturn(search);

        Exception exception = assertThrows(SearchException.class, () -> new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call());
        assertThat(exception.getMessage()).contains("Failed to parse query [AND mydoc]");
        verify(repository).setState(eq(search.uuid), any(SearchException.class));
    }

    @Test
    public void test_search_with_error_in_queries() throws Exception {
        String queryBody = "{\"bool\":{\"must\":[{\"match_all\":{}},{\"bool\":{\"should\":[{\"query_string\":{\"query\":\"<query>\"}}]}},{\"match\":{\"type\":\"Document\"}}]}}";
        Document mydoc = createDoc("docId1").with("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("\"mydoc"), null, User.local(),false, null, queryBody,
                null, 0);
        when(repository.get(local(), search.uuid)).thenReturn(search);

        Exception exception = assertThrows(SearchException.class, () -> new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call());
        assertThat(exception.getMessage()).contains("Unexpected char");
        verify(repository).setState(eq(search.uuid), any(SearchException.class));
    }

    @Test
    public void test_use_batch_search_scroll_size_value_over_scroll_size_value() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put(SCROLL_SIZE_OPT, "100");
            put(BATCH_SEARCH_SCROLL_SIZE_OPT, "0");
        }});
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), null, User.local(), false, null, null,null, 0);
        when(repository.get(local(), search.uuid)).thenReturn(search);

        Exception exception = assertThrows(SearchException.class, () -> new BatchSearchRunner(indexer, propertiesProvider, repository, taskView(search), progressCb).call());
        assertThat(exception.getMessage()).contains("[size] cannot be [0] in a scroll context");
        verify(repository).setState(eq(search.uuid), any(SearchException.class));
    }

    @Test
    public void test_use_scroll_size_value() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put(SCROLL_SIZE_OPT, "0");
        }});
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), null, User.local(), false, null, null,null, 0);
        when(repository.get(local(), search.uuid)).thenReturn(search);

        Exception exception = assertThrows(SearchException.class, () -> new BatchSearchRunner(indexer, propertiesProvider, repository, taskView(search), progressCb).call());
        assertThat(exception.getMessage()).contains("[size] cannot be [0] in a scroll context");
        verify(repository).setState(eq(search.uuid), any(SearchException.class));
    }

    @Test
    public void test_use_scroll_duration_value() throws Exception {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put(BATCH_SEARCH_SCROLL_DURATION_OPT, "foo");
        }});
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), null, User.local(), false, null, null,null, 0);
        when(repository.get(local(), search.uuid)).thenReturn(search);

        Exception exception = assertThrows(SearchException.class, () -> new BatchSearchRunner(indexer, propertiesProvider, repository, taskView(search), progressCb).call());
        assertThat(exception.getMessage()).contains("failed to parse setting [scroll] with value [foo] as a time value: unit is missing or unrecognized");
        verify(repository).setState(eq(search.uuid), any(SearchException.class));
    }

    @Before
    public void setUp() { initMocks(this);}
}
