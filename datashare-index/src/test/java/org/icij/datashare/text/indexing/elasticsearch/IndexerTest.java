package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.lucene.document.Document;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.UnknownHostException;

import static org.fest.assertions.Assertions.assertThat;

public class IndexerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider());

    @Test
    public void test_get_unknown_document() throws Exception {
        Document doc = indexer.get("unknown");
        assertThat(doc).isNull();
    }

    public IndexerTest() throws UnknownHostException {}
}
