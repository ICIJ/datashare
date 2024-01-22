package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.HashMap;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class StatusResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-06-30T15:31:00Z");
    @Mock Repository repository;
    @Mock DataBus dataBus;
    @Mock DocumentCollectionFactory<Path> documentCollectionFactory;
    @Mock Indexer indexer;
    @Mock DocumentQueue<Path> queue;

    @Before
    public void setUp() {
        initMocks(this);
        when(documentCollectionFactory.createQueue(any(),eq(new PropertiesProvider().get(PropertiesProvider.QUEUE_NAME_OPTION).orElse("extract:queue")), eq(Path.class))).thenReturn(mock(DocumentQueue.class));
        configure(routes -> routes.add(new StatusResource(new PropertiesProvider(),repository,indexer,dataBus,documentCollectionFactory)));
    }

    @Test
    public void test_get_status_ok() {
        when(repository.getHealth()).thenReturn(true);
        when(indexer.getHealth()).thenReturn(true);
        when(dataBus.getHealth()).thenReturn(true);
        get("/api/status").should().respond(200).
                contain("\"database\":true").
                contain("\"index\":true").
                contain("\"database\":true");
    }

    @Test
    public void test_get_index_status() {
        when(indexer.getHealth()).thenReturn(true);
        get("/api/status").should().respond(503).contain("\"index\":true");
    }

    @Test
    public void test_get_database_status_when_down() {
        when(repository.getHealth()).thenReturn(false);
        when(indexer.getHealth()).thenReturn(true);
        get("/api/status").should().respond(503).contain("\"database\":false").haveType("application/json");
    }

    @Test
    public void test_get_index_status_when_down() {
        when(dataBus.getHealth()).thenReturn(true);
        when(indexer.getHealth()).thenReturn(false);
        when(repository.getHealth()).thenReturn(true);
        get("/api/status").should().respond(504).contain("\"index\":false").haveType("application/json");
    }
    
    @Test
    public void test_get_index_status_prevails_on_others() {
        when(indexer.getHealth()).thenReturn(false);
        get("/api/status").should().respond(504).contain("\"index\":false").haveType("application/json");
    }

    @Test
    public void test_get_dataBus_status_when_down() {
        when(dataBus.getHealth()).thenReturn(false);
        when(indexer.getHealth()).thenReturn(true);
        get("/api/status").should().respond(503).contain("\"databus\":false").haveType("application/json");
    }

    @Test
    public void test_get_status_with_open_metrics_format() {
        when(dataBus.getHealth()).thenReturn(true);
        get("/api/status?format=openmetrics").should().respond(200).haveType("text/plain;version=0.0.4").contain("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE datashare gauge\n" +
                "datashare{status=\"KO\",resource=\"database\"} 0 1593531060000\n" +
                "datashare{status=\"KO\",resource=\"index\"} 0 1593531060000\n" +
                "datashare{status=\"OK\",resource=\"databus\"} 1 1593531060000\n" +
                "datashare{status=\"OK\",resource=\"document_queue_status\"} 1 1593531060000\n" +
                "datashare{resource=\"document_queue_size\"} 0 1593531060000");
    }

    @Test
    public void test_get_status_with_open_metrics_format_with_platform_name() {
        configure(routes -> routes.add(new StatusResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("platform", "platform");
        }}),repository,indexer,dataBus,documentCollectionFactory)));
        when(dataBus.getHealth()).thenReturn(true);
        get("/api/status?format=openmetrics").should().respond(200).haveType("text/plain;version=0.0.4").contain("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE datashare gauge\n" +
                "datashare{environment=\"platform\",status=\"KO\",resource=\"database\"} 0 1593531060000\n" +
                "datashare{environment=\"platform\",status=\"KO\",resource=\"index\"} 0 1593531060000\n" +
                "datashare{environment=\"platform\",status=\"OK\",resource=\"databus\"} 1 1593531060000\n" +
                "datashare{environment=\"platform\",status=\"OK\",resource=\"document_queue_status\"} 1 1593531060000\n" +
                "datashare{environment=\"platform\",resource=\"document_queue_size\"} 0 1593531060000");
    }

    @Test
    public void test_get_queue_status() {
        get("/api/status").should().respond(504).
                contain("\"document_queue_status\":true").
                contain("\"document_queue_size\":0");
    }

    @Test
    public void test_get_queue_with_io_exception() {
        DocumentQueue<Path> mockQueue = mock(DocumentQueue.class);
        when(mockQueue.size()).thenThrow(new RuntimeException("test"));
        when(indexer.getHealth()).thenReturn(true);
        when(documentCollectionFactory.createQueue(any(),eq(new PropertiesProvider().get(PropertiesProvider.QUEUE_NAME_OPTION).orElse("extract:queue")), eq(Path.class))).thenReturn(mockQueue);
        configure(routes -> routes.add(new StatusResource(new PropertiesProvider(),repository,indexer,dataBus,documentCollectionFactory)));
        get("/api/status").should().respond(503).contain("\"document_queue_status\":false");
    }
}
