package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.function.TerFunction;
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

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class BatchSearchRunnerIntTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");

    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    @After public void tearDown() throws IOException { es.removeAll();}
    @Mock TerFunction<String, String, List<Document>, Boolean> resultConsumer;

    @Test
    public void test_search_with_file_types_ok() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(), false, singletonList("text/plain"), null, null, 0);
        new BatchSearchRunner(indexer, new PropertiesProvider(), search, resultConsumer).call();
        verify(resultConsumer).apply(search.uuid, "mydoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_file_types_ko() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(), false, singletonList("application/pdf"), null,null, 0);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchKo, resultConsumer).call();

        verify(resultConsumer, never()).apply(eq(searchKo.uuid), eq("mydoc"), anyList());
    }

    @Test
    public void test_search_with_paths_ok() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(),false, null, null,
                singletonList("/path/to"), 0);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchOk, resultConsumer).call();

        verify(resultConsumer).apply(searchOk.uuid, "mydoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_paths_ko() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(),false, null, null,
                singletonList("/foo/bar"), 0);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchKo, resultConsumer).call();

        verify(resultConsumer, never()).apply(eq(searchKo.uuid), eq("mydoc"), anyList());
    }

    @Test
    public void test_search_with_fuzziness() throws Exception {
        Document mydoc = createDoc("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo1 = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("doc"), User.local(),false, null, null,
                null, 1);
        BatchSearch searchKo2 = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("nodoc"), User.local(),false, null, null,
                null, 1);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("hedoc"), User.local(),false, null, null,
                null, 2);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchKo1, resultConsumer).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), searchKo2, resultConsumer).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), searchOk, resultConsumer).call();

        verify(resultConsumer, never()).apply(eq(searchKo1.uuid), eq("doc"), anyList());
        verify(resultConsumer, never()).apply(eq(searchKo2.uuid), eq("nodoc"), anyList());
        verify(resultConsumer).apply(searchOk.uuid, "hedoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_phraseMatches() throws Exception {
        Document mydoc = createDoc("docId").with("mydoc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("to find mydoc"), User.local(),false, null, null,
                null, true);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc to find"), User.local(),false, null, null,
                null,true);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchKo, resultConsumer).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), searchOk, resultConsumer).call();

        verify(resultConsumer, never()).apply(eq(searchKo.uuid), eq("to find mydoc"), anyList());
        verify(resultConsumer).apply(searchOk.uuid, "mydoc to find", singletonList(mydoc));
    }

    @Test
    public void test_search_with_phraseMatches_with_ner() throws Exception {
        Document mydoc = createDoc("docId").with("anne's doc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        indexer.add(TEST_INDEX, NamedEntity.create(NamedEntity.Category.PERSON, "anne", asList(12L), mydoc.getId(), mydoc.getRootDocument(), Pipeline.Type.CORENLP, Language.FRENCH));
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("anne doc"), User.local(),false, null, null,
                null, true);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("anne's doc"), User.local(),false, null, null,
                null,true);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchKo, resultConsumer).call();
        new BatchSearchRunner(indexer, new PropertiesProvider(), searchOk, resultConsumer).call();

        verify(resultConsumer, never()).apply(eq(searchKo.uuid), eq("anne doc"), anyList());
        verify(resultConsumer).apply(searchOk.uuid, "anne's doc", singletonList(mydoc));
    }

    @Test
    public void test_search_phrase_matches_with_slop() throws Exception {
        // with phrase match a permutation (they call it transposition) is 2 slop
        // https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-match-query-phrase.html
        Document mydoc = createDoc("docId").with("mydoc find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("find mydoc"), User.local(), false, null, null,
                 null, 2,true);

        new BatchSearchRunner(indexer, new PropertiesProvider(), search, resultConsumer).call();

        verify(resultConsumer).apply(search.uuid, "find mydoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_operators() throws Exception {
        Document mydoc1 = createDoc("docId1").with("mydoc one").build();
        Document mydoc2 = createDoc("docId2").with("mydoc two").build();
        indexer.add(TEST_INDEX, mydoc1);
        indexer.add(TEST_INDEX, mydoc2);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc AND one"), User.local());

        new BatchSearchRunner(indexer, new PropertiesProvider(), search, resultConsumer).call();

        verify(resultConsumer).apply(search.uuid, "mydoc AND one", singletonList(mydoc1));
    }

    @Test
    public void test_search_with_query_template() throws Exception {
        String queryBody = "{\"bool\":{\"must\":[{\"match_all\":{}},{\"bool\":{\"should\":[{\"query_string\":{\"query\":\"<query>\"}}]}},{\"match\":{\"type\":\"Document\"}}]}}";
        Document mydoc = createDoc("docId").with("mydoc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(),false, null, queryBody,
                null, 0);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchOk, resultConsumer).call();

        verify(resultConsumer).apply(searchOk.uuid, "mydoc", singletonList(mydoc));
    }


    @Test
    public void test_search_without_query_template() throws Exception {
        Document mydoc = createDoc("docId").with("mydoc to find").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch searchOk = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(),false, null, null,
                null, 0);

        new BatchSearchRunner(indexer, new PropertiesProvider(), searchOk, resultConsumer).call();

        verify(resultConsumer).apply(searchOk.uuid, "mydoc", singletonList(mydoc));
    }

    @Test
    public void test_search_with_error() throws Exception {
        Document mydoc = createDoc("docId1").with("mydoc").build();
        indexer.add(TEST_INDEX, mydoc);
        BatchSearch search = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("AND mydoc"), User.local());

        ElasticsearchException eex = assertThrows(ElasticsearchException.class,() -> new BatchSearchRunner(indexer, new PropertiesProvider(), search, resultConsumer).call());

        assertThat(eex.error().toString()).contains("Failed to parse query [AND mydoc]");
    }

    @Test(expected = ElasticsearchException.class)
    public void test_use_batch_search_scroll_size_value_over_scroll_size_value() {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put(SCROLL_SIZE_OPT, "100");
            put(BATCH_SEARCH_SCROLL_SIZE_OPT, "0");
        }});
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(), false, null, null,null, 0);

        new BatchSearchRunner(indexer, propertiesProvider, searchKo, resultConsumer).call();
    }

    @Test(expected = ElasticsearchException.class)
    public void test_use_scroll_size_value() {
        PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put(SCROLL_SIZE_OPT, "0");
        }});
        BatchSearch searchKo = new BatchSearch(singletonList(project(TEST_INDEX)), "name", "desc", asSet("mydoc"), User.local(), false, null, null,null, 0);

        new BatchSearchRunner(indexer, propertiesProvider, searchKo, resultConsumer).call();
    }

    @Before
    public void setUp() { initMocks(this);}
}
