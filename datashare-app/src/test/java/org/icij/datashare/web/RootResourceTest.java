package org.icij.datashare.web;


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
    public void test_get_version() {
        get("/version").should().respond(200);
    }
}
