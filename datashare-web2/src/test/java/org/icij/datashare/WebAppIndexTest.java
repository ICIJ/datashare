package org.icij.datashare;

import org.junit.Test;

public class WebAppIndexTest extends AbstractWebAppTest {
    @Test
    public void testRoot() throws Exception {
        get("/").should().contain("Datashare REST API");
    }

    @Test
    public void testEmptySearch() {
        post("/index/search", "{}").should().beEmpty();
    }
}
