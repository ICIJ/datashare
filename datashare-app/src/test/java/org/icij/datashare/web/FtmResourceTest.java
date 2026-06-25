package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
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
    @Mock JooqRepository jooqRepository;
    MockIndexer mockIndexer;

    @Test
    public void test_doc_id() {
        mockIndexer.indexFile("local-datashare", "doc_id", Path.of("path/to/doc.pdf"), "application/pdf");
        get("/api/ftm/local-datashare/doc_id").should().respond(200).haveType("application/json")
                .contain("path/to/doc.pdf")
                .not().contain("null");
    }

    @Test
    public void test_doc_id_not_found() {
        get("/api/ftm/local-datashare/unknown_doc_id").should().respond(404);
    }

    @Test
    public void test_doc_id_forbidden_for_non_member_project() {
        mockIndexer.indexFile("foo_index", "doc_id", Path.of("path/to/doc.pdf"), "application/pdf");
        get("/api/ftm/foo_index/doc_id").should().respond(403);
    }

    @Before
    public void setUp() {
        initMocks(this);
        this.mockIndexer = new MockIndexer(mockEs);
        configure(routes -> routes.add(new FtmResource(mockEs))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }
}
