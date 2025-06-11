package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.GetRequest;
import jakarta.json.JsonException;
import java.util.Objects;
import org.apache.http.ConnectionClosedException;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Duplicate;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.ExtractedText;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.indexing.SearchedText;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEXES;
import static org.icij.datashare.text.Document.Status.DONE;
import static org.icij.datashare.text.Document.Status.INDEXED;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.NamedEntity.create;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.icij.datashare.text.indexing.ScrollQueryBuilder.createScrollQuery;
import static org.icij.datashare.text.nlp.Pipeline.Type.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ElasticsearchIndexerTest {
    static final String KEEP_ALIVE = "60000ms";
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule(TEST_INDEXES);
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    @After
    public void tearDown() throws Exception {
        es.removeAll();
    }

    @Test
    public void test_get_unknown_document() {
        Document doc = indexer.get(TEST_INDEX, "unknown");
        assertThat(doc).isNull();
    }

    @Test
    public void test_bulk_add() throws IOException {
        Document doc1 = createDoc("doc1").build();
        assertThat(indexer.bulkAdd(TEST_INDEX, asList(doc1, createDoc("doc2").build()))).isTrue();

        assertThat(((Document) indexer.get(TEST_INDEX, "doc1"))).isEqualTo(doc1);
        assertThat(((Document) indexer.get(TEST_INDEX, "doc2"))).isNotNull();
    }

    @Test
    public void test_bulk_add_with_root_document() throws IOException {
        Document root = createDoc("root").build();
        assertThat(indexer.bulkAdd(TEST_INDEX, asList(createDoc("doc1").withParentId(root.getId()).withRootId(root.getId()).build(), createDoc("doc2").withParentId(root.getId()).withRootId(root.getId()).build()))).isTrue();

        assertThat(((Document) indexer.get(TEST_INDEX, "doc1")).getRootDocument()).isEqualTo(root.getId());
        assertThat(((Document) indexer.get(TEST_INDEX, "doc2")).getRootDocument()).isEqualTo(root.getId());
        assertThat(Objects.requireNonNull(es.client.get(GetRequest.of(d -> d.index(TEST_INDEX).id("doc1")), Document.class).source()).getRootDocument()).isEqualTo(root.getId());
        assertThat(Objects.requireNonNull(es.client.get(GetRequest.of(d -> d.index(TEST_INDEX).id("doc1")), Document.class).source()).getRootDocument()).isEqualTo(root.getId());
    }

    @Test
    public void test_bulk_add_named_entities() throws IOException {
        Document doc = createDoc("id").build();
        indexer.add(TEST_INDEX, doc);
        NamedEntity ne1 = create(PERSON, "John Doe", singletonList(12L), "doc.txt", "root", CORENLP, Language.FRENCH);
        NamedEntity ne2 = create(ORGANIZATION, "AAA", singletonList(123L), "doc.txt", "root", CORENLP, Language.FRENCH);

        assertThat(indexer.bulkAdd(TEST_INDEX, CORENLP, asList(ne1, ne2), doc)).isTrue();

        assertThat(((Document) indexer.get(TEST_INDEX, doc.getId())).getStatus()).isEqualTo(Document.Status.DONE);
        assertThat(((Document) indexer.get(TEST_INDEX, doc.getId())).getNerTags()).containsOnly(CORENLP);
        assertThat((NamedEntity) indexer.get(TEST_INDEX, ne1.getId(), doc.getId())).isNotNull();
        assertThat((NamedEntity) indexer.get(TEST_INDEX, ne2.getId(), doc.getId())).isNotNull();
    }

    @Test
    public void test_bulk_add_should_add_ner_pipeline_once_and_for_empty_list() throws IOException {
        Document doc = createDoc("id").with(INDEXED).with(OPENNLP).build();
        indexer.add(TEST_INDEX, doc);

        assertThat(indexer.bulkAdd(TEST_INDEX, OPENNLP, emptyList(), doc)).isTrue();

        co.elastic.clients.elasticsearch.core.GetResponse<Document> resp = es.client.get(co.elastic.clients.elasticsearch.core.GetRequest.of(d -> d.index(TEST_INDEX).id(doc.getId())), Document.class);
        assertThat(Objects.requireNonNull(resp.source()).getStatus()).isEqualTo(DONE);
        assertThat(resp.source().getNerTags()).containsOnly(OPENNLP);
    }

    @Test
    public void test_bulk_add_for_embedded_doc() throws IOException {
        Path path = Paths.get("mail.eml");
        Document parent = createDoc("id")
                .with(path)
                .with("content")
                .with(Language.FRENCH)
                .ofContentType("message/rfc822")
                .with(new HashMap<>())
                .with(INDEXED)
                .withContentLength(321L)
                .build();
        Document child = createDoc("childId")
                .with(path)
                .with("mail body")
                .with(Language.FRENCH)
                .ofContentType("text/plain")
                .with(new HashMap<>())
                .with(INDEXED)
                .withContentLength(123L)
                .withRootId("id")
                .withParentId("id")
                .withExtractionLevel((short)1)
                .build();

        indexer.add(TEST_INDEX,parent);
        indexer.add(TEST_INDEX,child);
        NamedEntity ne1 = create(PERSON, "Jane Daffodil", singletonList(12L), parent.getId(), "root", CORENLP, Language.FRENCH);

        assertThat(indexer.bulkAdd(TEST_INDEX,CORENLP, singletonList(ne1), child)).isTrue();

        Document doc = indexer.get(TEST_INDEX, child.getId(), parent.getId());
        assertThat(doc.getNerTags()).containsOnly(CORENLP);
        assertThat(doc.getStatus()).isEqualTo(Document.Status.DONE);
        NamedEntity actual = indexer.get(TEST_INDEX, ne1.getId(), doc.getRootDocument());
        assertThat(actual).isNotNull();
        assertThat(actual.getRootDocument()).isEqualTo(doc.getRootDocument());
    }

    @Test
    public void test_update_named_entity() throws IOException {
        Document parent = createDoc("id")
                .with(Paths.get("doc.txt"))
                .with("content Madeline")
                .with(Language.FRENCH)
                .ofContentType("text/plain")
                .with(new HashMap<>())
                .with(DONE)
                .withContentLength(123L)
                .build();
        NamedEntity ne = create(PERSON, "Madeline", singletonList(8L), parent.getId(), "root", CORENLP, Language.ENGLISH);
        indexer.add(TEST_INDEX, parent);
        indexer.add(TEST_INDEX, ne);

        ne.hide();
        indexer.update(TEST_INDEX, ne);

        NamedEntity neFromES = indexer.get(TEST_INDEX, ne.getId(), parent.getId());
        assertThat(neFromES.isHidden()).isTrue();
    }

    @Test
    public void test_search_no_results() throws IOException {
        List<? extends Entity> lst = indexer.search(singletonList(TEST_INDEX), Document.class).execute().collect(toList());
        assertThat(lst).isEmpty();
    }

    @Test
    public void test_search_with_multiple_indices() throws IOException {
        Document doc = createDoc("id")
                .with(Paths.get("doc.txt"))
                .with("content")
                .with(Language.FRENCH)
                .with("application/pdf")
                .with(INDEXED).build();
        indexer.add(TEST_INDEXES[1], doc);
        indexer.add(TEST_INDEXES[2], doc);

        List<? extends Entity> lst = indexer.search(asList(TEST_INDEXES[1], TEST_INDEXES[2]), Document.class).execute().toList();
        assertThat(lst.size()).isEqualTo(2);
    }

    @Test
    public void test_index_exists() throws IOException {
        Boolean exists = indexer.exists(TEST_INDEX);
        assertThat(exists).isTrue();
    }

    @Test
    public void test_index_doesnt_exist() throws IOException {
        Boolean exists = indexer.exists("non_existing_index");
        assertThat(exists).isFalse();
    }

    @Test
    public void test_duplicate() throws IOException {
        indexer.add(TEST_INDEX, new Duplicate(Paths.get("duplicate"), "docId"));
        assertThat(indexer.search(singletonList(TEST_INDEX), Duplicate.class)).isNotNull();
    }

    @Test
    public void test_search_with_status() throws IOException {
        Document doc = createDoc("id").with(INDEXED).build();
        indexer.add(TEST_INDEX, doc);

        List<? extends Entity> lst = indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(INDEXED).execute().toList();
        assertThat(lst.size()).isEqualTo(1);
        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).execute().count()).isEqualTo(0);
    }

    @Test
    public void test_search_with_json_query() throws IOException {
        Document doc = createDoc("id").build();
        indexer.add(TEST_INDEX, doc);

        String queryBody = "{\"bool\":{\"must\":[{\"match_all\":{}},{\"bool\":{\"should\":[{\"query_string\":{\"query\":\"*\"}}]}},{\"match\":{\"type\":\"Document\"}}]}}";
        List<? extends Entity> lst = indexer.search(singletonList(TEST_INDEX),Document.class, new SearchQuery(queryBody)).execute().toList();
        assertThat(lst.size()).isEqualTo(1);
    }

    @Test
    public void test_search_with_a_template_json_query_phrase_matches() throws IOException {
        Document doc = createDoc("id").with("content with john doe").build();
        indexer.add(TEST_INDEX, doc);
        String queryBody = "{\"bool\":{\"must\":[{\"match_all\":{}},{\"bool\":{\"should\":[{\"query_string\":{\"query\":\"<query>\"}}]}},{\"match\":{\"type\":\"Document\"}}]}}";
        String queryToSearch = "john AND doe";

        List<? extends Entity> searchWithPhraseMatch = indexer.search(singletonList(TEST_INDEX),Document.class, new SearchQuery(queryBody)).
                with(0, true).execute(queryToSearch).toList();
        assertThat(searchWithPhraseMatch.size()).isEqualTo(0);

        List<? extends Entity> searchWithoutPhraseMatch = indexer.search(singletonList(TEST_INDEX),Document.class, new SearchQuery(queryBody)).
                with(0, false).execute(queryToSearch).toList();
        assertThat(searchWithoutPhraseMatch.size()).isEqualTo(1);
    }

    @Test
    public void test_search_with_a_template_json_query_fuzziness() throws IOException {
        Document doc1 = createDoc("id1").with("bar").build();
        Document doc2 = createDoc("id2").with("baz").build();
        indexer.add(TEST_INDEX, doc1);
        indexer.add(TEST_INDEX, doc2);
        String queryBody = "{\"bool\":{\"must\":[{\"match_all\":{}},{\"bool\":{\"should\":[{\"query_string\":{\"query\":\"<query>\"}}]}},{\"match\":{\"type\":\"Document\"}}]}}";
        String queryToSearch = "bar";

        List<? extends Entity> searchWithFuzziness0 = indexer.search(singletonList(TEST_INDEX),Document.class, new SearchQuery(queryBody)).
                with(0, false).execute(queryToSearch).toList();
        assertThat(searchWithFuzziness0.size()).isEqualTo(1);

        List<? extends Entity> searchWithFuzziness1 = indexer.search(singletonList(TEST_INDEX),Document.class, new SearchQuery(queryBody)).
                with(1, false).execute(queryToSearch).toList();
        assertThat(searchWithFuzziness1.size()).isEqualTo(2);
    }

    @Test
    public void test_tag_document() throws IOException {
        Document doc = createDoc("id").build();
        indexer.add(TEST_INDEX, doc);

        assertThat(indexer.tag(project(TEST_INDEX), doc.getId(), doc.getId(), tag("foo"), tag("bar"))).isTrue();
        assertThat(indexer.tag(project(TEST_INDEX), doc.getId(), doc.getId(), tag("foo"))).isFalse();

        List<? extends Entity> lst = indexer.search(singletonList(TEST_INDEX), Document.class).with(tag("foo"), tag("bar")).execute().toList();
        assertThat(lst.size()).isEqualTo(1);
        assertThat(((Document)lst.get(0)).getTags()).containsOnly(tag("foo"), tag("bar"));
    }

    @Test
    public void test_tag_document_without_tags_field_for_backward_compatibility() throws IOException {
        Document doc = createDoc("id").build();
        indexer.add(TEST_INDEX, doc);
        UpdateRequest removeTagsRequest = new UpdateRequest(TEST_INDEX, doc.getId()).script(new Script(ScriptType.INLINE, "painless", "ctx._source.remove(\"tags\")", new HashMap<>()));
        removeTagsRequest.setRefreshPolicy(IMMEDIATE);
        //es.client.update(removeTagsRequest, RequestOptions.DEFAULT);

        assertThat(indexer.tag(project(TEST_INDEX), doc.getId(), doc.getId(), tag("tag"))).isTrue();
    }

    @Test(expected = ElasticsearchException.class)
    public void test_tag_unknown_document() throws IOException {
        indexer.tag(project(TEST_INDEX), "unknown", "routing", tag("foo"), tag("bar"));
    }

    @Test
    public void test_untag_document() throws IOException {
        Document doc = createDoc("id").build();

        indexer.add(TEST_INDEX, doc);
        indexer.tag(project(TEST_INDEX), doc.getId(), doc.getId(), tag("foo"), tag("bar"), tag("bar"), tag("baz"));

        assertThat(indexer.untag(project(TEST_INDEX), doc.getId(),doc.getId(), tag("baz"), tag("foo"))).isTrue();
        assertThat(((Document)indexer.get(TEST_INDEX, doc.getId())).getTags()).containsOnly(tag("bar"));
        assertThat(indexer.untag(project(TEST_INDEX), doc.getId(),doc.getId(), tag("foo"))).isFalse();

        assertThat(indexer.untag(project(TEST_INDEX), doc.getId(),doc.getId(), tag("bar"))).isTrue();
        assertThat(((Document)indexer.get(TEST_INDEX, doc.getId())).getTags()).isEmpty();
    }

   @Test
    public void test_group_tag_untag_documents() throws IOException {
        Document doc1 = createDoc("id1").build();
        Document doc2 = createDoc("id2").build();
        indexer.add(TEST_INDEX, doc1);
        indexer.add(TEST_INDEX, doc2);

        assertThat(indexer.tag(project(TEST_INDEX), asList("id1", "id2"), tag("foo"), tag("bar"))).isTrue();
        assertThat(((Document)indexer.get(TEST_INDEX, "id1")).getTags()).containsOnly(tag("foo"), tag("bar"));
        assertThat(((Document)indexer.get(TEST_INDEX, "id2")).getTags()).containsOnly(tag("foo"), tag("bar"));

        assertThat(indexer.untag(project(TEST_INDEX), asList("id1", "id2"), tag("foo"), tag("bar"))).isTrue();
        assertThat(((Document)indexer.get(TEST_INDEX, "id1")).getTags()).isEmpty();
        assertThat(((Document)indexer.get(TEST_INDEX, "id2")).getTags()).isEmpty();
    }

    @Test
    public void test_search_with_field_value() throws Exception {
        indexer.add(TEST_INDEX, create(PERSON, "Joe Foo", singletonList(2L), "docId", "root", CORENLP, Language.FRENCH));
        indexer.add(TEST_INDEX, create(PERSON, "John Doe", singletonList(12L), "docId", "root", CORENLP, Language.FRENCH));
        indexer.add(TEST_INDEX, create(PERSON, "John Doe", singletonList(24L), "doc2Id", "root", CORENLP, Language.FRENCH));

        assertThat(indexer.search(singletonList(TEST_INDEX), NamedEntity.class).thatMatchesFieldValue("mentionNorm", "john doe").execute().count()).isEqualTo(2);
        assertThat(indexer.search(singletonList(TEST_INDEX), NamedEntity.class).thatMatchesFieldValue("offsets", 24).execute().count()).isEqualTo(1);
    }

    @Test
    public void test_search_with_and_without_NLP_tags() throws IOException {
        Document doc = createDoc("id").with(DONE).with(CORENLP, OPENNLP).build();
        indexer.add(TEST_INDEX,doc);

        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).without(CORENLP).execute().count()).isEqualTo(0);
        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).without(CORENLP, OPENNLP).execute().count()).isEqualTo(0);

        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).without(SPACY).execute().count()).isEqualTo(1);
        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).with(CORENLP).execute().count()).isEqualTo(1);
        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).with(OPENNLP).execute().count()).isEqualTo(1);
        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).with(CORENLP, OPENNLP).execute().count()).isEqualTo(1);
        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).ofStatus(DONE).with(CORENLP, SPACY).execute().count()).isEqualTo(1);
    }

    @Test
    public void test_search_with_and_without_NLP_tags_no_tags() throws IOException {
        Document doc = createDoc("id").with(INDEXED).build();
        indexer.add(TEST_INDEX,doc);

        assertThat((int) indexer.search(singletonList(TEST_INDEX), Document.class).without().execute().count()).isEqualTo(1);
    }

    @Test
    public void test_search_source_filtering() throws IOException {
        Document doc = createDoc("id").ofContentType("application/pdf").build();
        indexer.add(TEST_INDEX,doc);

        Document actualDoc = (Document) indexer.search(singletonList(TEST_INDEX),Document.class).withSource("contentType").execute().toList().get(0);
        assertThat(actualDoc.getContentType()).isEqualTo("application/pdf");
        assertThat(actualDoc.getId()).isEqualTo(doc.getId());
        assertThat(actualDoc.getContent()).isEmpty();
    }

    @Test
    public void test_search_source_false() throws IOException {
        Document doc = createDoc("id").ofContentType("application/pdf").build();
        indexer.add(TEST_INDEX,doc);

        Document actualDoc = (Document) indexer.search(singletonList(TEST_INDEX),Document.class).withSource(false).execute().toList().get(0);
        assertThat(actualDoc.getId()).isNotNull();
    }

    @Test
    public void test_search_size_limit() throws IOException {
        for (int i = 0 ; i < 20; i++) {
            Document doc = createDoc("id" + i).build();
            indexer.add(TEST_INDEX,doc);
        }
        assertThat(indexer.search(singletonList(TEST_INDEX),Document.class).limit(5).execute().count()).isEqualTo(5);
        assertThat(indexer.search(singletonList(TEST_INDEX),Document.class).execute().count()).isEqualTo(20);
    }

    @Test
    public void test_search_with_scroll() throws IOException {
        for (int i = 0 ; i < 12; i++) {
            Document doc = createDoc("id" + i).build();
            indexer.add(TEST_INDEX,doc);
        }

        Indexer.Searcher searcher = indexer.search(singletonList(TEST_INDEX), Document.class).limit(5);
        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(5);
        assertThat(searcher.totalHits()).isEqualTo(12);
        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(5);
        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(2);
        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(0);
        searcher.clearScroll();
    }

    @Test
    public void test_scroll_with_json_query() throws IOException {
        for (int i = 0; i < 12; i++) {
            Document doc = createDoc("id" + i).build();
            indexer.add(TEST_INDEX, doc);
        }
        Indexer.Searcher searcher = indexer.search(singletonList(TEST_INDEX), Document.class,
                new SearchQuery("{\"bool\":{\"must\":[{\"match\":{\"type\":\"Document\"}}]}}")).limit(6);

        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(6);
        assertThat(searcher.totalHits()).isEqualTo(12);
        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(6);
        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(0);
        searcher.clearScroll();
    }
    @Test
    public void test_scroll_with_json_query_template() throws IOException {
        for (int i = 0; i < 12; i++) {
            Document doc = createDoc("id" + i).build();
            indexer.add(TEST_INDEX, doc);
        }
        Indexer.Searcher searcher = indexer.search(singletonList(TEST_INDEX), Document.class,
                new SearchQuery("{\"bool\":{\"must\":[{\"query_string\":{\"query\":\"<query>\"}}, {\"match\":{\"type\":\"Document\"}}]}}"))
                .limit(12);

        assertThat(searcher.scroll(KEEP_ALIVE, "id*").count()).isEqualTo(12);
        assertThat(searcher.totalHits()).isEqualTo(12);

        try {
            searcher.scroll(createScrollQuery().withStringQuery("other query").build());
            fail("should throw IllegalStateException");
        } catch (IllegalStateException ilex) {
            assertThat(ilex.getMessage()).isEqualTo("cannot change query when scroll is pending");
        }

        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(0);
        searcher.clearScroll();
    }

    @Test(expected = JsonException.class)
    public void test_scroll_with_json_query_template_and_wrong_query() throws IOException {
        Document doc = createDoc("id").build();
        indexer.add(TEST_INDEX, doc);

        Indexer.Searcher searcher = indexer.search(singletonList(TEST_INDEX), Document.class,
                        new SearchQuery("{\"bool\":{\"must\":[{\"query_string\":{\"query\":\"<query>\"}}, {\"match\":{\"type\":\"Document\"}}]}}"));

        searcher.scroll(KEEP_ALIVE, "\"id*");

        searcher.clearScroll();
    }

    @Test(expected = IllegalStateException.class)
    public void test_searcher_scroll_is_not_usable_after_clear() throws IOException {
        Indexer.Searcher searcher = indexer.search(singletonList(TEST_INDEX), Document.class).limit(5);
        assertThat(searcher.scroll(KEEP_ALIVE).count()).isEqualTo(0);

        searcher.clearScroll();

        searcher.scroll(KEEP_ALIVE);
    }

    @Test
    public void test_bulk_update() throws IOException {
        Document doc = createDoc("id").build();
        indexer.add(TEST_INDEX, doc);
        NamedEntity ne1 = create(PERSON, "John Doe", singletonList(12L), doc.getId(), "root", CORENLP, Language.FRENCH);
        NamedEntity ne2 = create(ORGANIZATION, "AAA", singletonList(123L), doc.getId(), "root", CORENLP, Language.FRENCH);
        indexer.bulkAdd(TEST_INDEX, CORENLP, asList(ne1, ne2), doc);

        ne1.hide();
        ne2.hide();
        assertThat(indexer.bulkUpdate(TEST_INDEX, asList(ne1, ne2))).isTrue();

        Object[] namedEntities = indexer.search(singletonList(TEST_INDEX), NamedEntity.class).execute().toArray();
        assertThat(namedEntities.length).isEqualTo(2);
        assertThat(((NamedEntity)namedEntities[0]).isHidden()).isTrue();
        assertThat(((NamedEntity)namedEntities[1]).isHidden()).isTrue();
    }

    @Test
    public void test_delete_by_query() throws Exception {
        Document doc = createDoc("docId").build();
        indexer.add(TEST_INDEX, doc);
        indexer.add(TEST_INDEX, create(PERSON, "Joe Foo", singletonList(2L), "docId", "root", CORENLP, Language.FRENCH));
        indexer.add(TEST_INDEX, create(PERSON, "John Doe", singletonList(12L), "docId", "root", CORENLP, Language.FRENCH));

        assertThat(indexer.deleteAll(TEST_INDEX)).isTrue();

        Object[] documents = indexer.search(singletonList(TEST_INDEX), Document.class).execute().toArray();
        assertThat(documents.length).isEqualTo(0);
    }

    @Test
    public void test_delete_all_non_existing_index() throws Exception {
        assertThat(indexer.deleteAll("non_existing_index")).isFalse();
    }

    @Test
    public void test_query_like_js_front_finds_document_from_its_child_named_entity() throws Exception {
        Document doc = createDoc("id").with("content with john doe").build();
        indexer.add(TEST_INDEX, doc);
        NamedEntity ne1 = create(PERSON, "John Doe", singletonList(12L), doc.getId(), "root", CORENLP, Language.FRENCH);
        indexer.bulkAdd(TEST_INDEX, CORENLP, singletonList(ne1), doc);

        Object[] documents = indexer.search(singletonList(TEST_INDEX), Document.class, new SearchQuery("john")).withoutSource("content").execute().toArray();

        assertThat(documents.length).isEqualTo(1);
        assertThat(((Document)documents[0]).getId()).isEqualTo("id");
        assertThat(((Document)documents[0]).getContent()).isEmpty();
    }

    @Test
    public void test_execute_raw_search() throws Exception {
        Document doc = createDoc("id").with("my content").with(OPENNLP).build();
        indexer.add(TEST_INDEX, doc);
        assertThat(indexer.executeRaw("POST", TEST_INDEX + "/_search", "{\"query\":{\"match_all\":{}}}")).contains("my content");
        assertThat(indexer.executeRaw("POST", TEST_INDEX + "/_search", "{\"query\":{\"match\":{\"content\":\"foo\"}}}")).doesNotContain("my content");
    }

    @Test
    public void test_execute_raw_with_head() throws Exception {
        assertThat(indexer.executeRaw("HEAD", TEST_INDEX, "")).isNull();
        assertThat(indexer.executeRaw("HEAD", TEST_INDEX, null)).isNull();
    }

    @Test
    public void test_execute_raw_with_options() throws Exception {
        assertThat(indexer.executeRaw("OPTIONS", TEST_INDEX, "").split(",")).containsOnly("PUT","HEAD","DELETE","GET");
        assertThat(indexer.executeRaw("OPTIONS", TEST_INDEX, null).split(",")).containsOnly("PUT","HEAD","DELETE","GET");
    }

    @Test
    public void test_es_index_status() {
        assertThat(indexer.getHealth()).isTrue();
    }

    @Test
    public void test_es_index_status_connection_closed() throws IOException {
        ElasticsearchIndexer indexerSpy = spy(indexer);
        when(indexerSpy.ping()).thenThrow(new ConnectionClosedException());
        assertThat(indexerSpy.getHealth()).isFalse();
    }

    @Test
    public void test_search_query_with_operator_and_phrase_match() throws Exception {
        Document doc = createDoc("id").with("content with john doe").build();
        indexer.add(TEST_INDEX, doc);

        assertThat(indexer.search(singletonList(TEST_INDEX),Document.class, new SearchQuery("john AND doe")).with( 0, true).execute().toArray()).isEmpty();
        assertThat(indexer.search(singletonList(TEST_INDEX),Document.class, new SearchQuery("john AND doe")).with( 0, false).execute().toArray()).hasSize(1);
    }

    @Test
    public void test_get_slice_of_document_content() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);

        ExtractedText actual = indexer.getExtractedText(TEST_INDEX, "id", null, 0, 10, null);
        assertThat(actual.content).isEqualTo("content wi");
        assertThat(actual.content.length()).isEqualTo(10);
        assertThat(actual.maxOffset).isEqualTo(21);
    }
    @Test
    public void test_get_slice_of_document_content_with_offset() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);

        ExtractedText actual = indexer.getExtractedText(TEST_INDEX, "id", null, 10, 10, null);
        assertThat(actual.content).isEqualTo("th john do");
        assertThat(actual.content.length()).isEqualTo(10);
    }
    @Test
    public void test_get_slice_of_document_content_with_maxOffset() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);

        ExtractedText actual = indexer.getExtractedText(TEST_INDEX, "id", null, 20, 1, null);
        assertThat(actual.content).isEqualTo("e");
        assertThat(actual.content.length()).isEqualTo(1);
    }
    @Test
    public void test_get_slice_of_document_content_with_no_limit() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);

        ExtractedText actual = indexer.getExtractedText(TEST_INDEX, "id", null, 21, 0, null);
        assertThat(actual.content).isEqualTo("");
        assertThat(actual.content.length()).isEqualTo(0);
    }
    @Test(expected = IndexOutOfBoundsException.class)
    public void test_get_slice_of_document_content_with_negative_limit() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);
        indexer.getExtractedText(TEST_INDEX, "id", null, -10, 1, null);
    }
    @Test(expected = IndexOutOfBoundsException.class)
    public void test_get_slice_of_document_content_with_negative_start() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);
        indexer.getExtractedText(TEST_INDEX, "id", null, 1, -10, null);
    }
    @Test(expected = IndexOutOfBoundsException.class)
    public void test_get_slice_of_document_content_with_oversize() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);

        indexer.getExtractedText(TEST_INDEX, "id", null, 0, 22, null);
    }
    @Test(expected = IndexOutOfBoundsException.class)
    public void test_get_slice_of_document_content_with_out_of_range_limit() throws Exception {
        Document doc = createDoc("id").with("content with john doe").withContentLength(34L).build();
        indexer.add(TEST_INDEX, doc);

        indexer.getExtractedText(TEST_INDEX, "id", null, 10, 18, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_get_slice_of_document_not_found() throws Exception {
        indexer.getExtractedText(TEST_INDEX, "id", null, 10, 18, null);
    }
    @Test(expected = IllegalArgumentException.class)
    public void test_get_slice_of_translated_document_not_found() throws Exception {
        Map<String, String> english = new HashMap<>(){{
            put("content","hello world");
            put("target_language","ENGLISH");
        }};
        List<Map<String,String>> translated_content = new ArrayList<>() {{add(english);}};

        Document doc = createDoc("id").with("bonjour monde").with(FRENCH).with(translated_content).withContentLength(13L).build();
        indexer.add(TEST_INDEX, doc);

        indexer.getExtractedText(TEST_INDEX, "id", null, 10, 18, "unknown");
    }

    @Test
    public void test_get_slice_of_translated_document_that_exists() throws Exception {
        Map<String, String> english = new HashMap<>(){{
            put("content","content with john doe");
            put("target_language","ENGLISH");
        }};
        List<Map<String,String>> contentTranslated = new ArrayList<>(){{add(english);}} ;
        Document doc = createDoc("id").with("contenu avec john doe").with(FRENCH).with(contentTranslated).withContentLength(21L).build();
        indexer.add(TEST_INDEX, doc);

        ExtractedText actual = indexer.getExtractedText(TEST_INDEX, "id", null, 0, 7, "ENGLISH");
        assertThat(actual.content).isEqualTo("content");
        assertThat(actual.content.length()).isEqualTo(7);
    }
    @Test
    public void test_search_occurrences_of_query_in_content_of_existing_document() throws Exception {
        Document doc = createDoc("id").with("this content contains content containing john doe").withContentLength(49L).build();
        indexer.add(TEST_INDEX, doc);

        SearchedText actual = indexer.searchTextOccurrences(TEST_INDEX, "id", "cont",null);
        assertThat(actual.query).isEqualTo("cont");
        assertThat(actual.count).isEqualTo(4);
        assertArrayEquals(actual.offsets, new int[]{5,13,22,30});
    }
    @Test
    public void test_search_occurrences_of_query_with_diacritics() throws Exception {
        Document doc = createDoc("id").with("contigüe et accentué s'est tueTuE").withContentLength(38L).build();
        indexer.add(TEST_INDEX, doc);

        SearchedText actual = indexer.searchTextOccurrences(TEST_INDEX, "id", "tué",null);
        assertThat(actual.query).isEqualTo("tué");
        assertThat(actual.count).isEqualTo(3);
        assertArrayEquals(new int[]{17,27,30},actual.offsets);
    }

    @Test
    public void test_search_occurrences_of_query_with_diacritics_long() throws Exception {
        String text= "L’en-½tête UDP n’a pas de notion\nMethod...tete de numérotation tête";
        Document doc = createDoc("id").with(text).withContentLength(86L).build();
        indexer.add(TEST_INDEX, doc);

        SearchedText actual = indexer.searchTextOccurrences(TEST_INDEX, "id", "tête",null);
        assertThat(actual.query).isEqualTo("tête");
        assertThat(actual.count).isEqualTo(3);
        assertArrayEquals(new int[]{6, 42, 63},actual.offsets);
    }

    @Test
    public void test_search_occurrences_of_query_in_content_of_existing_document_ignoring_case() throws Exception {
        Document doc = createDoc("id").with("this content contains content containing john doe").withContentLength(49L).build();
        indexer.add(TEST_INDEX, doc);

        SearchedText actual = indexer.searchTextOccurrences(TEST_INDEX, "id", "CONT",null);
        assertThat(actual.query).isEqualTo("CONT");
        assertThat(actual.count).isEqualTo(4);
        assertArrayEquals(actual.offsets, new int[]{5,13,22,30});
    }
    @Test
    public void test_search_occurrences_of_query_in_translated_content_of_existing_document() throws Exception {
        Map<String, String> french = new HashMap<>(){{
            put("content","ce contenu contient du contenu contenant john doe");
            put("target_language","FRENCH");
        }};
        List<Map<String,String>> contentTranslated = new ArrayList<>(){{add(french);}};
        Document doc = createDoc("id").with("this content contains content containing john doe")
                .withContentLength(49L)
                .with(ENGLISH).with(contentTranslated).build();
        indexer.add(TEST_INDEX, doc);

        SearchedText actual = indexer.searchTextOccurrences(TEST_INDEX, "id", "cont","FRENCH");
        assertThat(actual.query).isEqualTo("cont");
        assertThat(actual.count).isEqualTo(4);
        assertThat(actual.targetLanguage).isEqualTo("FRENCH");
        assertArrayEquals(actual.offsets, new int[]{3,11,23,31});
    }
    @Test
    public void test_retrieve_script_from_resource_file() throws IOException {
        String filename= "extractedText.painless.java";
        String res= ElasticsearchIndexer.getScriptStringFromFile(filename);
        assertThat(res.length()).isEqualTo(1784);
        assertThat(res).isEqualTo(ElasticsearchIndexer.getMemoizeScript().get(filename));
    }

    @Test(expected = FileNotFoundException.class)
    public void test_retrieve_script_from_unknown_resource_file() throws IOException {
        String filename= "unknown.painless.java";
        ElasticsearchIndexer.getScriptStringFromFile(filename);
    }
}
