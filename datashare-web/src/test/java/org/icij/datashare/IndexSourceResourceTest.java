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
    public void test_get_source_file_with_routing() throws Exception {
        File htmlFile = new File(temp.getRoot(), "index.html");
        write(htmlFile, "<html>content</html>");
        indexFile("local-datashare", "id_html", htmlFile.toPath(), null, "my_routing");

        get("/api/index/src/local-datashare/id_html?routing=my_routing").should().contain("<html>content</html>").haveType("text/html;charset=UTF-8");
    }

    @Test
    public void test_get_source_file_forbidden_index() {
        get("/api/index/src/foo_index/id").should().respond(403);
    }

    private void indexFile(String index, String _id, Path path, String contentType, String routing) {
        Document doc = mock(Document.class);
        when(doc.getPath()).thenReturn(path);
        when(doc.getName()).thenReturn(path.getFileName().toString());
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

