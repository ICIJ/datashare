package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.util.HashMap;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class StatusResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-06-30T15:31:00Z");
    @Mock Repository repository;
    @Mock Indexer indexer;
    @Mock TaskManager taskManager;

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> routes.add(new StatusResource(new PropertiesProvider(),repository,indexer,taskManager)));
    }

    @Test
    public void test_get_status_ok() throws Exception{
        when(repository.getHealth()).thenReturn(true);
        when(indexer.getHealth()).thenReturn(true);
        when(taskManager.getHealth()).thenReturn(true);
        get("/api/status").should().respond(200).
                contain("\"database\":true").
                contain("\"index\":true").
                contain("\"taskManager\":true");
    }

    @Test
    public void test_get_index_status() {
        when(indexer.getHealth()).thenReturn(true);
        get("/api/status").should().respond(503).contain("\"index\":true");
    }

    @Test
    public void test_get_database_status_when_down() throws Exception {
        when(repository.getHealth()).thenReturn(false);
        when(indexer.getHealth()).thenReturn(true);
        when(taskManager.getHealth()).thenReturn(true);
        get("/api/status").should().respond(503).contain("\"database\":false").haveType("application/json");
    }

    @Test
    public void test_get_taskmanager_status_when_down() throws Exception {
        when(repository.getHealth()).thenReturn(true);
        when(indexer.getHealth()).thenReturn(true);
        when(taskManager.getHealth()).thenReturn(false);
        get("/api/status").should().respond(503).contain("\"taskManager\":false").haveType("application/json");
    }


    @Test
    public void test_get_index_status_when_down() throws Exception {
        when(indexer.getHealth()).thenReturn(false);
        when(repository.getHealth()).thenReturn(true);
        when(taskManager.getHealth()).thenReturn(true);
        get("/api/status").should().respond(504).contain("\"index\":false").haveType("application/json");
    }
    
    @Test
    public void test_get_index_status_prevails_on_others() {
        when(indexer.getHealth()).thenReturn(false);
        get("/api/status").should().respond(504).contain("\"index\":false").haveType("application/json");
    }

    @Test
    public void test_get_status_with_open_metrics_format() {
        get("/api/status?format=openmetrics").should().respond(200).haveType("text/plain;version=0.0.4").contain("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE datashare gauge\n" +
                "datashare{status=\"KO\",resource=\"database\"} 0 1593531060000\n" +
                "datashare{status=\"KO\",resource=\"index\"} 0 1593531060000\n" +
                "datashare{status=\"KO\",resource=\"taskManager\"} 0 1593531060000\n");
    }

    @Test
    public void test_get_status_with_open_metrics_format_with_platform_name() {
        configure(routes -> routes.add(new StatusResource(new PropertiesProvider(new HashMap<>() {{
            put("platform", "platform");
        }}),repository,indexer,taskManager)));
        get("/api/status?format=openmetrics").should().respond(200).haveType("text/plain;version=0.0.4").contain("" +
                "# HELP datashare The datashare resources status\n" +
                "# TYPE datashare gauge\n" +
                "datashare{environment=\"platform\",status=\"KO\",resource=\"database\"} 0 1593531060000\n" +
                "datashare{environment=\"platform\",status=\"KO\",resource=\"index\"} 0 1593531060000\n" +
                "datashare{environment=\"platform\",status=\"KO\",resource=\"taskManager\"} 0 1593531060000\n");
    }
}
