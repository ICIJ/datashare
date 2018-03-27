package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.HashMap;

import static java.util.Arrays.asList;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;

public class IndexerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);

    @Test
    public void test_get_unknown_document() throws Exception {
        Document doc = indexer.get("unknown");
        assertThat(doc).isNull();
    }

    @Test
    public void test_bulk_add() throws IOException {
        Document doc = new org.icij.datashare.text.Document(Paths.get("doc.txt"), "content", Language.FRENCH, Charset.defaultCharset(), "application/pdf", new HashMap<>(), org.icij.datashare.text.Document.Status.INDEXED);
        indexer.add(doc);
        NamedEntity ne1 = NamedEntity.create(PERSON, "John Doe", 12, "doc.txt", Pipeline.Type.CORENLP, Language.FRENCH);
        NamedEntity ne2 = NamedEntity.create(ORGANIZATION, "AAA", 123, "doc.txt", Pipeline.Type.CORENLP, Language.FRENCH);
        assertThat(indexer.bulkAdd(asList(ne1, ne2), doc)).isTrue();

        assertThat(((Document) indexer.get(doc.getId())).getStatus()).isEqualTo(Document.Status.DONE);
        assertThat((NamedEntity) indexer.get(ne1.getId(), doc.getId())).isNotNull();
        assertThat((NamedEntity) indexer.get(ne2.getId(), doc.getId())).isNotNull();
    }

    public IndexerTest() throws UnknownHostException {}
}
