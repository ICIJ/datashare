package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Language;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.file.Paths.get;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.Document.Status.INDEXED;
import static org.icij.datashare.text.Project.project;

public class ElasticsearchExtractedStreamerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private ElasticsearchExtractedStreamer streamer = new ElasticsearchExtractedStreamer(
            new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE), TEST_INDEX);

    @After
    public void tearDown() throws Exception {
        es.removeAll();
    }

    @Test
    public void test_stream_path() throws Exception {
        streamer.indexer.add(TEST_INDEX, new org.icij.datashare.text.Document(project("prj"), get("/dir/doc1.txt"),
                "content1", Language.FRENCH, Charset.defaultCharset(), "text/plain", new HashMap<>(), INDEXED, 432L));
        streamer.indexer.add(TEST_INDEX, new org.icij.datashare.text.Document(project("prj"), get("/dir/doc2.txt"),
                "content2", Language.FRENCH, Charset.defaultCharset(), "text/plain", new HashMap<>(), INDEXED, 352L));

        Set<Path> paths = streamer.extractedDocuments().collect(Collectors.toSet());

        assertThat(paths).containsOnly(get("/dir/doc1.txt"), get("/dir/doc2.txt"));
    }
}
