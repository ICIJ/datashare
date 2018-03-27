package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Document.Status.DONE;
import static org.icij.datashare.text.Document.Status.INDEXED;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.text.nlp.Pipeline.Type.*;

public class IndexerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

    @After
    public void tearDown() throws Exception {
        es.removeAll();
    }

    @Test
    public void test_get_unknown_document() throws Exception {
        Document doc = indexer.get("unknown");
        assertThat(doc).isNull();
    }

    @Test
    public void test_bulk_add() throws IOException {
        Document doc = new org.icij.datashare.text.Document(Paths.get("doc.txt"), "content",
                Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED);
        indexer.add(doc);
        NamedEntity ne1 = NamedEntity.create(PERSON, "John Doe", 12, "doc.txt", CORENLP, Language.FRENCH);
        NamedEntity ne2 = NamedEntity.create(ORGANIZATION, "AAA", 123, "doc.txt", MITIE, Language.FRENCH);

        assertThat(indexer.bulkAdd(asList(ne1, ne2), doc)).isTrue();

        assertThat(((Document) indexer.get(doc.getId())).getStatus()).isEqualTo(Document.Status.DONE);
        assertThat(((Document) indexer.get(doc.getId())).getNerTags()).containsOnly(CORENLP, MITIE);
        assertThat((NamedEntity) indexer.get(ne1.getId(), doc.getId())).isNotNull();
        assertThat((NamedEntity) indexer.get(ne2.getId(), doc.getId())).isNotNull();
    }

    @Test
    public void test_bulk_add_should_merge_ner_tags() throws IOException {
        Document doc = new org.icij.datashare.text.Document(Paths.get("doc.txt"), "content", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED,
                new HashSet<Pipeline.Type>() {{ add(OPENNLP);}});
        indexer.add(doc);

        assertThat(indexer.bulkAdd(
                singletonList(NamedEntity.create(PERSON, "Jane Die", 18, "doc.txt", CORENLP, Language.FRENCH)),
                doc)).isTrue();

        assertThat(((Document) indexer.get(doc.getId())).getNerTags()).containsOnly(CORENLP, OPENNLP);
    }

    @Test
    public void test_bulk_add_for_embedded_doc() throws IOException {
        Document parent = new org.icij.datashare.text.Document(Paths.get("mail.eml"), "content",
                Language.FRENCH, Charset.defaultCharset(), "message/rfc822", new HashMap<>(), INDEXED);
        Document child = new org.icij.datashare.text.Document(Paths.get("mail.eml"), "mail body",
                Language.FRENCH, Charset.defaultCharset(), "text/plain", new HashMap<>(), INDEXED, new HashSet<>(), parent.getId());
        indexer.add(parent);
        indexer.add(child);
        NamedEntity ne1 = NamedEntity.create(PERSON, "Jane Daffodil", 12, parent.getId(), CORENLP, Language.FRENCH);

        assertThat(indexer.bulkAdd(singletonList(ne1), child)).isTrue();

        Entity doc = indexer.get(child.getId(), parent.getId());
        assertThat(((Document) doc).getNerTags()).containsOnly(CORENLP);
        assertThat(((Document) doc).getStatus()).isEqualTo(Document.Status.DONE);
        assertThat((NamedEntity) indexer.get(ne1.getId(), doc.getId())).isNotNull();
    }

    @Test
    public void test_search_of_status() {
        Document doc = new org.icij.datashare.text.Document(Paths.get("doc.txt"), "content", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED);
        indexer.add(doc);

        List<? extends Entity> lst = indexer.search(Document.class).ofStatus(INDEXED).execute().collect(toList());
        assertThat(lst.size()).isEqualTo(1);
        assertThat(indexer.search(Document.class).ofStatus(DONE).execute().collect(toList()).size()).isEqualTo(0);
    }

    @Test
    public void test_search_source_filtering() {
        Document doc = new org.icij.datashare.text.Document(Paths.get("doc_with_parent.txt"), "content", Language.FRENCH,
                Charset.defaultCharset(), "application/pdf", new HashMap<>(), INDEXED, new HashSet<>(), "parent");
        indexer.add(doc);

        Document actualDoc = (Document) indexer.search(Document.class).withSource("parentDocument").execute().collect(toList()).get(0);
        assertThat(actualDoc.getParentDocument()).isEqualTo("parent");
        assertThat(actualDoc.getId()).isNotNull();
        assertThat(actualDoc.getContent()).isEmpty();
    }


    public IndexerTest() throws UnknownHostException {}
}
