package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.PropertiesProvider.propertiesToMap;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class ScanIndexTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("defaultProject", TEST_INDEX);
        put("reportName", "test:report");
        put("stages", "SCANIDX");
    }});
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    private final MemoryDocumentCollectionFactory<Path> documentCollectionFactory = new MemoryDocumentCollectionFactory<>();

    @Test
    public void test_empty_index() throws Exception {
        assertThat(new ScanIndexTask(documentCollectionFactory, indexer, new Task(
                ScanIndexTask.class.getName(), User.nullUser(), propertiesToMap(propertiesProvider.getProperties())), null).call().value()).isEqualTo(0L);
    }

    @Test
    public void test_transfer_indexed_paths_to_filter_set() throws Exception {
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id1").build());
        indexer.add(TEST_INDEX, DocumentBuilder.createDoc("id2").build());

        assertThat(new ScanIndexTask(documentCollectionFactory, indexer,  new Task(
                ScanIndexTask.class.getName(), User.nullUser(), propertiesToMap(propertiesProvider.getProperties())), null).call().value()).isEqualTo(2L);

        ReportMap actualReportMap = documentCollectionFactory.createMap("test:report");
        assertThat(actualReportMap).includes(
                entry(Paths.get("/path/to/id1"), new Report(ExtractionStatus.SUCCESS)),
                entry(Paths.get("/path/to/id2"), new Report(ExtractionStatus.SUCCESS))
        );
    }

    @After
    public void tearDown() throws IOException {
        es.removeAll();
    }
}
