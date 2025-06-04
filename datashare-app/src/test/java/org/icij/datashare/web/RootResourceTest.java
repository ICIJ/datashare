package org.icij.datashare.web;


import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

public class RootResourceTest extends AbstractProdWebServerTest {
    @Before
    public void setUp() {
        configure(routes -> routes.add(RootResource.class));
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
                contain("ds.extractorVersion");
    }
}
