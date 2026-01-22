package org.icij.datashare.web;


import org.apache.tika.Tika;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class RootResourceTest extends AbstractProdWebServerTest {
    @Mock Indexer indexer;

    @Before
    public void setUp() throws IOException {
        initMocks(this);
        when(indexer.getVersion()).thenReturn(Map.of("version", "2.0.0", "distribution", "opensearch"));
        configure(routes -> routes.add(new RootResource(new PropertiesProvider(), indexer)));
    }

    @Test
    public void test_get_public_config() {
        get("/settings").should().respond(200);
    }

    @Test
    public void test_get_public_config_for_batch_download_max_nb_files() {
        get("/settings").should()
                    .respond(200)
                    .haveType("application/json")
                    .contain("batchDownloadMaxNbFiles");
    }

    @Test
    public void test_get_public_config_for_batch_download_max_size() {
        get("/settings").should()
                    .respond(200)
                    .haveType("application/json")
                    .contain("batchDownloadMaxSize");
    }

    @Test
    public void test_get_public_config_for_path_separator() {
        get("/settings").should()
                .respond(200)
                .haveType("application/json")
                .contain("pathSeparator");
    }

    @Test
    public void test_get_version() {
        get("/version").should().respond(200).
                contain(Tika.getString()).
                contain("ds.extractorVersion").
                contain("index.version").
                contain("index.distribution");
    }

    @Test
    public void test_get_version_returns_index_version_and_distribution() {
        get("/version").should().respond(200).
                contain("\"index.version\":\"2.0.0\"").
                contain("\"index.distribution\":\"opensearch\"");
    }

    @Test
    public void test_get_version_returns_unknown_when_indexer_fails() throws IOException {
        when(indexer.getVersion()).thenThrow(new IOException("connection refused"));
        get("/version").should().respond(200).
                contain("\"index.version\":\"unknown\"").
                contain("\"index.distribution\":\"unknown\"");
    }
}
