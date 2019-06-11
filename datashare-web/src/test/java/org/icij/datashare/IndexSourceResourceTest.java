package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class IndexSourceResourceTest implements FluentRestTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    @Mock Indexer indexer;
    @Override public int port() { return server.port();}

    @Test
    public void test_get_source_file() throws Exception {
        File txtFile = new File(temp.getRoot(), "file.txt");
        write(txtFile, "text content");
        indexFile("local-datashare", "id_txt", txtFile.toPath(), null, null);

        get("/api/index/src/local-datashare/id_txt").should().contain("text content").haveType("text/plain;charset=UTF-8");
    }

    @Test
    public void test_get_source_file_with_content_type() throws Exception {
        File txtFile = new File(temp.getRoot(), "/my/path/to/file.ods");
        write(txtFile, "content");
        indexFile("local-datashare", "id_ods", txtFile.toPath(), "application/vnd.oasis.opendocument.spreadsheet", null);

        get("/api/index/src/local-datashare/id_ods").should().
                haveType("application/vnd.oasis.opendocument.spreadsheet").
                haveHeader("Content-Disposition", "attachment;filename=\"file.ods\"");
    }

    @Test
    public void test_get_source_file_with_content_type_inline() throws Exception {
        File img = new File(temp.getRoot(), "/my/path/to/image.jpg");
        write(img, "content");
        indexFile("local-datashare", "id_jpg", img.toPath(), "image/jpg", null);

        get("/api/index/src/local-datashare/id_jpg?inline=true").should().
                haveType("image/jpg").contain("content").
                should().not().haveHeader("Content-Disposition", "attachment;filename=\"image.jpg\"");
    }

    @Test
    public void test_get_embedded_source_file_with_routing() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        indexFile("local-datashare", "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623", Paths.get(path), "application/pdf", "id_eml");

        get("/api/index/src/local-datashare/d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623?routing=id_eml").
                should().haveType("application/pdf").contain("PDF-1.3").haveHeader("Content-Disposition", "attachment;filename=\"d365f488df.pdf\"");;
    }

    @Test
    public void test_source_file_not_found_should_return_404() {
        indexFile("local-datashare", "missing_file", Paths.get("missing/file"), null, null);
        get("/api/index/src/local-datashare/missing_file").should().respond(404);
    }

    @Test
    public void test_get_source_file_forbidden_index() {
        get("/api/index/src/foo_index/id").should().respond(403);
    }

    private void indexFile(String index, String _id, Path path, String contentType, String routing) {
        Document doc = mock(Document.class);
        when(doc.getPath()).thenReturn(path);
        when(doc.getId()).thenReturn(_id);
        when(doc.getName()).thenReturn(path.getFileName().toString());
        when(doc.isRootDocument()).thenReturn(routing == null);
        when(doc.getContentType()).thenReturn(contentType);
        if (routing == null) {
            when(indexer.get(index, _id)).thenReturn(doc);
        } else {
            when(indexer.get(index, _id, routing)).thenReturn(doc);
        }
    }
    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new IndexResource(new PropertiesProvider(), indexer)).filter(new LocalUserFilter(new PropertiesProvider())));
    }
    static void write(File file, String content) throws IOException {
        file.toPath().getParent().toFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(UTF_8));
    }
    @After public void tearDown() { reset(indexer);}
}

