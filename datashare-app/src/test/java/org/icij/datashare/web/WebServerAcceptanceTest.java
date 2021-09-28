package org.icij.datashare.web;

import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.mode.ServerMode;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

public class WebServerAcceptanceTest extends AbstractProdWebServerTest {
    @Before
    public void setUp() throws Exception {
        configure(new ServerMode(new DatashareCli().parseArguments(new String[]{"--cors=*"}).properties).createWebConfiguration());
        waitForDatashare();
    }

    @Test
    public void test_root_serve_app() {
        get("/").should().haveType("text/html").contain("<title>datashare-client</title>");
    }

    @Test
    public void test_should_have_cors() {
        get("/api/users/me").should().haveHeader("Access-Control-Allow-Origin", "*");
    }
}