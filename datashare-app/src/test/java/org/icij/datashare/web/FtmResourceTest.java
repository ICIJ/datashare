package org.icij.datashare.web;

import org.icij.datashare.tasks.MockIndexer;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.file.Path;

import static org.mockito.MockitoAnnotations.initMocks;

public class FtmResourceTest extends AbstractProdWebServerTest {
    @Mock Indexer mockEs;
    MockIndexer mockIndexer;

    @Test
    public void test_doc_id() {
        mockIndexer.indexFile("prj", "doc_id", Path.of("path/to/doc.pdf"), "application/pdf");
        get("/api/ftm/prj/doc_id").should().respond(200).haveType("application/json")
                .contain("path/to/doc.pdf")
                .not().contain("null");
    }

    @Test
    public void test_doc_id_not_found() {
        get("/api/ftm/prj/unknown_doc_id").should().respond(404);
    }

    @Before
    public void setUp() {
        initMocks(this);
        this.mockIndexer = new MockIndexer(mockEs);
        configure(routes -> routes.add(new FtmResource(mockEs)));
    }
}