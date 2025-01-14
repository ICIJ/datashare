package org.icij.datashare.web;

import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;

public class OpenApiResourceTest extends AbstractProdWebServerTest {

    @Test
    public void test_get() {
        get("/api/openapi").should().respond(200).
                haveType("text/json").
                contain("\"openapi\":\"3.0.1\"");
    }
    @Test
    public void test_get_yaml() {
        get("/api/openapi?format=yaml").should().respond(200).
                haveType("text/yaml").
                contain("openapi: 3.0.1");
    }

    @Before
    public void setUp() {
        configure(routes -> routes.add(new OpenApiResource()));
    }
}
