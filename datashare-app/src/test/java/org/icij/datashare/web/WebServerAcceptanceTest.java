package org.icij.datashare.web;

import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.String.format;

public class WebServerAcceptanceTest extends AbstractProdWebServerTest {
    @Before
    public void setUp() throws Exception {
        Path datashareHome = Files.createTempDirectory("datashare-");
        String[] args = {
            "--cors=*",
            "--mode=SERVER",
            format("--dataSourceUrl=jdbc:sqlite:file:%s/db.db", datashareHome),
            format("--pluginsDir=%s", datashareHome),
            format("--extensionsDir=%s", datashareHome)
        };
        configure(CommonMode.create(new DatashareCli().parseArguments(args).properties).createWebConfiguration());
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