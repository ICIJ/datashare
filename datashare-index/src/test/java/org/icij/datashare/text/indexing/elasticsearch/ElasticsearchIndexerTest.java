package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.*;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Document.Status.DONE;
import static org.icij.datashare.text.Document.Status.INDEXED;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.NamedEntity.create;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer.hasLuceneOperators;
import static org.icij.datashare.text.nlp.Pipeline.Type.*;

public class ElasticsearchIndexerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

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
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content",
                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(), 4324L);
        indexer.add(TEST_INDEX, doc);
        NamedEntity ne1 = create(PERSON, "John Doe", 12, "doc.txt", CORENLP, Language.FRENCH);
        NamedEntity ne2 = create(ORGANIZATION, "AAA", 123, "doc.txt", CORENLP, Language.FRENCH);

        assertThat(indexer.bulkAdd(TEST_INDEX, CORENLP, asList(ne1, ne2), doc)).isTrue();

        assertThat(((Document) indexer.get(TEST_INDEX, doc.getId())).getStatus()).isEqualTo(Document.Status.DONE);
        assertThat(((Document) indexer.get(TEST_INDEX, doc.getId())).getNerTags()).containsOnly(CORENLP);
        assertThat((NamedEntity) indexer.get(TEST_INDEX, ne1.getId(), doc.getId())).isNotNull();
        assertThat((NamedEntity) indexer.get(TEST_INDEX, ne2.getId(), doc.getId())).isNotNull();
    }

    @Test
    public void test_bulk_add_should_add_ner_pipeline_once_and_for_empty_list() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content",
                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(),
                INDEXED, new HashSet<Pipeline.Type>() {{ add(OPENNLP);}}, 432L);
        indexer.add(TEST_INDEX, doc);

        assertThat(indexer.bulkAdd(TEST_INDEX, OPENNLP, emptyList(), doc)).isTrue();

        GetResponse resp = es.client.get(new GetRequest(TEST_INDEX, "doc", doc.getId()));
        assertThat(resp.getSourceAsMap().get("status")).isEqualTo("DONE");
        assertThat((ArrayList<String>) resp.getSourceAsMap().get("nerTags")).containsExactly("OPENNLP");
    }

    @Test
    public void test_bulk_add_for_embedded_doc() throws IOException {
        Document parent = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("mail.eml"), "content",
                Language.FRENCH, Charset.defaultCharset(), "message/rfc822", new HashMap<>(), INDEXED, new HashSet<>(), 321L);
        Document child = new Document(project("prj"), "childId", Paths.get("mail.eml"), "mail body",
                            FRENCH, Charset.defaultCharset(),
                            "text/plain", new HashMap<>(), Document.Status.INDEXED,
                            new HashSet<>(), new Date(), "id", "id",
                (short) 1, 123L);
        indexer.add(TEST_INDEX,parent);
        indexer.add(TEST_INDEX,child);
        NamedEntity ne1 = create(PERSON, "Jane Daffodil", 12, parent.getId(), CORENLP, Language.FRENCH);

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
        Document parent = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content Madeline",
                        Language.FRENCH, Charset.defaultCharset(), "text/plain", new HashMap<>(), DONE, new HashSet<>(), 123L);
        NamedEntity ne = create(PERSON, "Madeline", 8, parent.getId(), CORENLP, Language.ENGLISH);
        indexer.add(TEST_INDEX, parent);
        indexer.add(TEST_INDEX, ne);

        ne.hide();
        indexer.update(TEST_INDEX, ne);

        NamedEntity neFromES = indexer.get(TEST_INDEX, ne.getId(), parent.getId());
        assertThat(neFromES.isHidden()).isTrue();
    }

    @Test
    public void test_search_no_results() throws IOException {
        List<? extends Entity> lst = indexer.search(TEST_INDEX,Document.class).execute().collect(toList());
        assertThat(lst).isEmpty();
    }

    @Test
    public void test_search_with_status() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(),123L);
        indexer.add(TEST_INDEX, doc);

        List<? extends Entity> lst = indexer.search(TEST_INDEX,Document.class).ofStatus(INDEXED).execute().collect(toList());
        assertThat(lst.size()).isEqualTo(1);
        assertThat(indexer.search(TEST_INDEX,Document.class).ofStatus(DONE).execute().collect(toList()).size()).isEqualTo(0);
    }

    @Test
    public void test_tag_document() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(),123L);
        indexer.add(TEST_INDEX, doc);

        assertThat(indexer.tag(project(TEST_INDEX), doc.getId(), doc.getId(), tag("foo"), tag("bar"))).isTrue();
        assertThat(indexer.tag(project(TEST_INDEX), doc.getId(), doc.getId(), tag("foo"))).isFalse();

        List<? extends Entity> lst = indexer.search(TEST_INDEX, Document.class).with(tag("foo"), tag("bar")).execute().collect(toList());
        assertThat(lst.size()).isEqualTo(1);
        assertThat(((Document)lst.get(0)).getTags()).containsOnly(tag("foo"), tag("bar"));
    }

    @Test
    public void test_tag_document_without_tags_field_for_backward_compatibility() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(),123L);
        indexer.add(TEST_INDEX, doc);
        UpdateRequest removeTagsRequest = new UpdateRequest(TEST_INDEX, "doc", doc.getId()).script(new Script(ScriptType.INLINE, "painless", "ctx._source.remove(\"tags\")", new HashMap<>()));
        removeTagsRequest.setRefreshPolicy(IMMEDIATE);
        es.client.update(removeTagsRequest);

        assertThat(indexer.tag(project(TEST_INDEX), doc.getId(), doc.getId(), tag("tag"))).isTrue();
    }

    @Test(expected = ElasticsearchStatusException.class)
    public void test_tag_unknown_document() throws IOException {
        indexer.tag(project(TEST_INDEX), "unknown", "routing", tag("foo"), tag("bar"));
    }

    @Test
    public void test_untag_document() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(),123L);
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
        Document doc1 = new org.icij.datashare.text.Document("id1", project("prj"), Paths.get("doc1.txt"), "content1", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(),123L);
        Document doc2 = new org.icij.datashare.text.Document("id2", project("prj"), Paths.get("doc2.txt"), "content2", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(),123L);
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
        indexer.add(TEST_INDEX, create(PERSON, "Joe Foo", 2, "docId", CORENLP, Language.FRENCH));
        indexer.add(TEST_INDEX, create(PERSON, "John Doe", 12, "docId", CORENLP, Language.FRENCH));
        indexer.add(TEST_INDEX, create(PERSON, "John Doe", 24, "doc2Id", CORENLP, Language.FRENCH));

        assertThat(indexer.search(TEST_INDEX, NamedEntity.class).thatMatchesFieldValue("mentionNorm", "john doe").execute().count()).isEqualTo(2);
        assertThat(indexer.search(TEST_INDEX, NamedEntity.class).thatMatchesFieldValue("documentId", "doc2Id").execute().count()).isEqualTo(1);
    }

    @Test
    public void test_search_with_and_without_NLP_tags() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content",
                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), DONE, new HashSet<Pipeline.Type>() {{ add(CORENLP); add(OPENNLP);}}, 123L);
        indexer.add(TEST_INDEX,doc);

        assertThat(indexer.search(TEST_INDEX,Document.class).ofStatus(DONE).without(CORENLP).execute().collect(toList()).size()).isEqualTo(0);
        assertThat(indexer.search(TEST_INDEX,Document.class).ofStatus(DONE).without(CORENLP, OPENNLP).execute().collect(toList()).size()).isEqualTo(0);

        assertThat(indexer.search(TEST_INDEX,Document.class).ofStatus(DONE).without(IXAPIPE).execute().collect(toList()).size()).isEqualTo(1);
        assertThat(indexer.search(TEST_INDEX,Document.class).ofStatus(DONE).with(CORENLP).execute().collect(toList()).size()).isEqualTo(1);
        assertThat(indexer.search(TEST_INDEX,Document.class).ofStatus(DONE).with(CORENLP, OPENNLP).execute().collect(toList()).size()).isEqualTo(1);
        assertThat(indexer.search(TEST_INDEX,Document.class).ofStatus(DONE).with(CORENLP, IXAPIPE).execute().collect(toList()).size()).isEqualTo(1);
    }

    @Test
    public void test_search_with_and_without_NLP_tags_no_tags() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content",
                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(), 345L);
        indexer.add(TEST_INDEX,doc);

        assertThat(indexer.search(TEST_INDEX,Document.class).without().execute().collect(toList()).size()).isEqualTo(1);
    }

    @Test
    public void test_search_source_filtering() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc_with_parent.txt"), "content",
                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(), 444L);
        indexer.add(TEST_INDEX,doc);

        Document actualDoc = (Document) indexer.search(TEST_INDEX,Document.class).withSource("contentType").execute().collect(toList()).get(0);
        assertThat(actualDoc.getContentType()).isEqualTo("application/pdf");
        assertThat(actualDoc.getId()).isEqualTo(doc.getId());
        assertThat(actualDoc.getContent()).isEmpty();
    }

    @Test
    public void test_query_with_lucene_reserved_chars() throws Exception {
        assertThat(hasLuceneOperators("a   b")).isFalse();
        assertThat(hasLuceneOperators(" a b  ")).isFalse();
        assertThat(hasLuceneOperators("a\\/b")).isFalse();
        assertThat(hasLuceneOperators("a & b")).isFalse();
        assertThat(hasLuceneOperators("a + b")).isFalse();
        assertThat(hasLuceneOperators("a - b")).isFalse();
        assertThat(hasLuceneOperators("a | b")).isFalse();
        assertThat(hasLuceneOperators("a \\\"b c\\\"")).isFalse();

        assertThat(hasLuceneOperators("a /b/")).isTrue();
        assertThat(hasLuceneOperators("f:a")).isTrue();
        assertThat(hasLuceneOperators("a f:b")).isTrue();
        assertThat(hasLuceneOperators("n:[1 TO 5]")).isTrue();
        assertThat(hasLuceneOperators("n:>10")).isTrue();
        assertThat(hasLuceneOperators("*a*")).isTrue();
        assertThat(hasLuceneOperators("?a?")).isTrue();
        assertThat(hasLuceneOperators("+a -b")).isTrue();
        assertThat(hasLuceneOperators("a \"b c\"")).isTrue();
        assertThat(hasLuceneOperators("a && b")).isTrue();
        assertThat(hasLuceneOperators("a || b")).isTrue();
        assertThat(hasLuceneOperators("a AND b")).isTrue();
        assertThat(hasLuceneOperators("a OR b")).isTrue();
        assertThat(hasLuceneOperators("a NOT b")).isTrue();
        assertThat(hasLuceneOperators("a^")).isTrue();
        assertThat(hasLuceneOperators("a^2")).isTrue();
        assertThat(hasLuceneOperators("a~")).isTrue();
        assertThat(hasLuceneOperators("a~2")).isTrue();
    }

    @Test
    public void test_search_source_false() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc_with_parent.txt"), "content",
                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(), 222L);
        indexer.add(TEST_INDEX,doc);

        Document actualDoc = (Document) indexer.search(TEST_INDEX,Document.class).withSource(false).execute().collect(toList()).get(0);
        assertThat(actualDoc.getId()).isNotNull();
    }

    @Test
    public void test_search_size_limit() throws IOException {
        for (int i = 0 ; i < 20; i++) {
            Document doc = new org.icij.datashare.text.Document("id" + i, project("prj"), Paths.get(format("doc%d.txt", i)), format("content %d", i), Language.ENGLISH,
                Charset.defaultCharset(), "text/plain", new HashMap<>(), DONE, new HashSet<>(), 666L);
            indexer.add(TEST_INDEX,doc);
        }
        assertThat(indexer.search(TEST_INDEX,Document.class).limit(5).execute().count()).isEqualTo(5);
        assertThat(indexer.search(TEST_INDEX,Document.class).execute().count()).isEqualTo(20);
    }

    @Test
    public void test_search_with_scroll() throws IOException {
        for (int i = 0 ; i < 12; i++) {
            Document doc = new org.icij.datashare.text.Document("id" + i, project("prj"), Paths.get(format("doc%d.txt", i)), format("content %d", i), Language.ENGLISH,
                Charset.defaultCharset(), "text/plain", new HashMap<>(), DONE, new HashSet<>(), 345L);
            indexer.add(TEST_INDEX,doc);
        }

        Indexer.Searcher searcher = indexer.search(TEST_INDEX, Document.class).limit(5);
        assertThat(searcher.scroll().count()).isEqualTo(5);
        assertThat(searcher.totalHits()).isEqualTo(12);
        assertThat(searcher.scroll().count()).isEqualTo(5);
        assertThat(searcher.scroll().count()).isEqualTo(2);
        assertThat(searcher.scroll().count()).isEqualTo(0);

        searcher.clearScroll();
        assertThat(searcher.scroll().count()).isEqualTo(5);
        searcher.clearScroll();
    }

    @Test
    public void test_bulk_update() throws IOException {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content",
                        Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(), 34L);
        indexer.add(TEST_INDEX, doc);
        NamedEntity ne1 = create(PERSON, "John Doe", 12, doc.getId(), CORENLP, Language.FRENCH);
        NamedEntity ne2 = create(ORGANIZATION, "AAA", 123, doc.getId(), CORENLP, Language.FRENCH);
        indexer.bulkAdd(TEST_INDEX, CORENLP, asList(ne1, ne2), doc);

        ne1.hide();
        ne2.hide();
        assertThat(indexer.bulkUpdate(TEST_INDEX, asList(ne1, ne2))).isTrue();

        Object[] namedEntities = indexer.search(TEST_INDEX, NamedEntity.class).execute().toArray();
        assertThat(namedEntities.length).isEqualTo(2);
        assertThat(((NamedEntity)namedEntities[0]).isHidden()).isTrue();
        assertThat(((NamedEntity)namedEntities[1]).isHidden()).isTrue();
    }

    @Test
    public void test_delete_by_query() throws Exception {
        indexer.add(TEST_INDEX, create(PERSON, "Joe Foo", 2, "docId", CORENLP, Language.FRENCH));
        indexer.add(TEST_INDEX, create(PERSON, "John Doe", 12, "docId", CORENLP, Language.FRENCH));

        assertThat(indexer.deleteAll(TEST_INDEX)).isTrue();

        Object[] documents = indexer.search(TEST_INDEX, Document.class).execute().toArray();
        assertThat(documents.length).isEqualTo(0);
    }

    @Test
    public void test_query_like_js_front_finds_document_from_its_child_named_entity() throws Exception {
        Document doc = new org.icij.datashare.text.Document("id", project("prj"), Paths.get("doc.txt"), "content with john doe",
                                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(), 34L);
        indexer.add(TEST_INDEX, doc);
        NamedEntity ne1 = create(PERSON, "John Doe", 12, doc.getId(), CORENLP, Language.FRENCH);
        indexer.bulkAdd(TEST_INDEX, CORENLP, singletonList(ne1), doc);

        Object[] documents = indexer.search(TEST_INDEX, Document.class).withoutSource("content").with("john").execute().toArray();

        assertThat(documents.length).isEqualTo(1);
        assertThat(((Document)documents[0]).getId()).isEqualTo("id");
        assertThat(((Document)documents[0]).getContent()).isEmpty();
    }

    public ElasticsearchIndexerTest() throws UnknownHostException {}
}
