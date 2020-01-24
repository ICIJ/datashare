package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.report.Report;
import org.icij.extract.report.ReportMap;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.stream.IntStream;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class ScanIndexTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
        put("defaultProject", TEST_INDEX);
        put("reportName", "test:report");
    }});
    private ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(IMMEDIATE);
    private MemoryDocumentCollectionFactory documentCollectionFactory = new MemoryDocumentCollectionFactory();

    @Test
    public void test_empty_index() throws Exception {
        assertThat(new ScanIndexTask(documentCollectionFactory, indexer, propertiesProvider, User.nullUser()).call()).isEqualTo(0);
    }

    @Test
    public void test_transfer_indexed_paths_to_filter_set() throws Exception {
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id1").build());
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id2").build());

        assertThat(new ScanIndexTask(documentCollectionFactory, indexer, propertiesProvider, User.nullUser()).call()).isEqualTo(2);

        ReportMap actualReportMap = documentCollectionFactory.createMap(propertiesProvider, "test:report");
        assertThat(actualReportMap).includes(
                entry(Paths.get("file:/path/to/id1"), new Report(ExtractionStatus.SUCCESS)),
                entry(Paths.get("file:/path/to/id2"), new Report(ExtractionStatus.SUCCESS))
        );
    }

    @Test
    public void test_bench() throws Exception {
        IntStream.range(0, 100).forEach(i -> {
            try {
                indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id" + i).build());
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        assertThat(new ScanIndexTask(documentCollectionFactory, indexer, propertiesProvider, User.nullUser()).call()).isEqualTo(100);

        ReportMap actualReportMap = documentCollectionFactory.createMap(propertiesProvider, "test:report");
        assertThat(actualReportMap).includes(
                entry(Paths.get("file:/path/to/id1"), new Report(ExtractionStatus.SUCCESS)),
                entry(Paths.get("file:/path/to/id2"), new Report(ExtractionStatus.SUCCESS))
        );
    }

    @After
    public void tearDown() throws IOException {
        es.removeAll();
    }
}
