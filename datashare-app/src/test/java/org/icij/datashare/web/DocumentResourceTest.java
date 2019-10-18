package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
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
import static java.util.Arrays.asList;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class DocumentResourceTest implements FluentRestTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    @Mock Repository repository;
    @Mock Indexer indexer;

    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new DocumentResource(repository, indexer)).filter(new LocalUserFilter(new PropertiesProvider())));
    }

    @Test
    public void test_get_source_file() throws Exception {
        File txtFile = new File(temp.getRoot(), "file.txt");
        write(txtFile, "text content");
        indexFile("local-datashare", "id_txt", txtFile.toPath(), null, null);

        get("/api/local-datashare/documents/src/id_txt").should().contain("text content").haveType("text/plain;charset=UTF-8");
    }

    @Test
    public void test_get_source_file_with_content_type() throws Exception {
        File txtFile = new File(temp.getRoot(), "/my/path/to/file.ods");
        write(txtFile, "content");
        indexFile("local-datashare", "id_ods", txtFile.toPath(), "application/vnd.oasis.opendocument.spreadsheet", null);

        get("/api/local-datashare/documents/src/id_ods").should().
                haveType("application/vnd.oasis.opendocument.spreadsheet").
                haveHeader("Content-Disposition", "attachment;filename=\"file.ods\"");
    }

    @Test
    public void test_get_source_file_with_content_type_inline() throws Exception {
        File img = new File(temp.getRoot(), "/my/path/to/image.jpg");
        write(img, "content");
        indexFile("local-datashare", "id_jpg", img.toPath(), "image/jpg", null);

        get("/api/local-datashare/documents/src/id_jpg?inline=true").should().
                haveType("image/jpg").contain("content").
                should().not().haveHeader("Content-Disposition", "attachment;filename=\"image.jpg\"");
    }

    @Test
    public void test_get_embedded_source_file_with_routing() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        indexFile("local-datashare", "d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623", Paths.get(path), "application/pdf", "id_eml");

        get("/api/local-datashare/documents/src/d365f488df3c84ecd6d7aa752ca268b78589f2082e4fe2fbe9f62dff6b3a6b74bedc645ec6df9ae5599dab7631433623?routing=id_eml").
                should().haveType("application/pdf").contain("PDF-1.3").haveHeader("Content-Disposition", "attachment;filename=\"d365f488df.pdf\"");;
    }

    @Test
    public void test_get_embedded_source_file_with_routing_sha256_for_backward_compatibility() {
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        indexFile("local-datashare", "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e", Paths.get(path), "application/pdf", "id_eml");

        get("/api/local-datashare/documents/src/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e?routing=id_eml").
                should().haveType("application/pdf").contain("PDF-1.3").haveHeader("Content-Disposition", "attachment;filename=\"6abb969509.pdf\"");;
    }

    @Test
    public void test_source_file_not_found_should_return_404() {
        indexFile("local-datashare", "missing_file", Paths.get("missing/file"), null, null);
        get("/api/local-datashare/documents/src/missing_file").should().respond(404);
    }

    @Test
    public void test_get_source_file_forbidden_index() {
        get("/api/foo_index/documents/src/id").should().respond(403);
    }

    @Test
    public void testStarDocument() {
        when(repository.star(any(), any())).thenReturn(true).thenReturn(false);
        put("/api/document/star/doc_id").should().respond(201);
        put("/api/document/star/doc_id").should().respond(200);
    }

    @Test
    public void testUnstarDocument() {
        when(repository.unstar(any(), any())).thenReturn(true).thenReturn(false);
        put("/api/document/unstar/doc_id").should().respond(201);
        put("/api/document/unstar/doc_id").should().respond(200);
    }

    @Test
    public void testGroupStarDocumentWithProject() {
        when(repository.star(project("prj1"), User.local(), asList("id1", "id2"))).thenReturn(2);
        post("/api/prj1/documents/batchUpdate/star", "[\"id1\", \"id2\"]").should().respond(200);
    }

    @Test
    public void testGroupUnstarDocumentWithProject() {
        when(repository.unstar(project("prj1"), User.local(), asList("id1", "id2"))).thenReturn(2);
        post("/api/prj1/documents/batchUpdate/unstar", "[\"id1\", \"id2\"]").should().respond(200);
    }

    @Test
    public void testTagDocumentWithProject() throws Exception {
        when(repository.tag(eq(project("prj1")), anyString(), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.tag(eq(project("prj1")), anyString(), anyString(), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        put("/api/prj1/documents/tags/doc_id", "[\"tag1\", \"tag2\"]").should().respond(201);
        put("/api/prj1/documents/tags/doc_id", "[\"tag1\", \"tag2\"]").should().respond(200);

        verify(indexer, times(2)).tag(eq(project("prj1")), eq("doc_id"), eq("doc_id"), eq(tag("tag1")), eq(tag("tag2")));
    }

    @Test
    public void testUntagDocumentWithProject() throws Exception {
        when(repository.untag(eq(project("prj2")), anyString(), eq(tag("tag3")), eq(tag("tag4")))).thenReturn(true).thenReturn(false);
        when(indexer.untag(eq(project("prj2")), anyString(), any(), eq(tag("tag3")), eq(tag("tag4")))).thenReturn(true).thenReturn(false);

        put("/api/document/project/untag/prj2/doc_id", "[\"tag3\", \"tag4\"]").should().respond(201);
        put("/api/document/project/untag/prj2/doc_id", "[\"tag3\", \"tag4\"]").should().respond(200);

        verify(indexer, times(2)).untag(eq(project("prj2")), anyString(), any(), any(), any());
    }

    @Test
    public void testGroupTagDocumentWithProject() throws Exception {
        when(repository.tag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.tag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        post("/api/document/project/prj1/group/tag", "{\"tags\": [\"tag1\", \"tag2\"], \"docIds\": [\"doc1\", \"doc2\"]}").should().respond(200);
    }

    @Test
    public void testGroupUntagDocumentWithProject() throws Exception {
        when(repository.untag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.untag(eq(project("prj1")), eq(asList("doc1", "doc2")), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        post("/api/document/project/prj1/group/untag", "{\"tags\": [\"tag1\", \"tag2\"], \"docIds\": [\"doc1\", \"doc2\"]}").should().respond(200);
    }

    @Test
    public void testGetTaggedDocumentsWithProject() {
        when(repository.getDocuments(eq(project("prj3")), eq(tag("foo")), eq(tag("bar")), eq(tag("baz")))).thenReturn(asList("id1", "id2"));
        get("/api/prj3/documents/tagged/foo,bar,baz").should().respond(200).contain("id1").contain("id2");
    }

    @Test
    public void testGetStarredDocumentsWithProject() {
        when(repository.getStarredDocuments(project("local-datashare"), User.local())).thenReturn(asList("id1", "id2"));
        get("/api/local-datashare/documents/starred").should().respond(200).contain("id1").contain("id2");
    }

    @Test
    public void testTagsForDocument() {
        when(repository.getTags(eq(project("prj")), eq("docId"))).thenReturn(asList(tag("tag1"), tag("tag2")));
        get("/api/document/project/prj/tag/docId").should().respond(200).contain("tag1").contain("tag2");
    }

    @Test
    public void testTagDocumentWithProjectWithRouting() throws Exception {
        when(repository.tag(eq(project("prj1")), anyString(), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);
        when(indexer.tag(eq(project("prj1")), any(), eq("routing"), eq(tag("tag1")), eq(tag("tag2")))).thenReturn(true).thenReturn(false);

        put("/api/prj1/documents/tags/doc_id?routing=routing", "[\"tag1\", \"tag2\"]").should().respond(201);
        put("/api/prj1/documents/tags/doc_id?routing=routing", "[\"tag1\", \"tag2\"]").should().respond(200);

        verify(indexer, times(2)).tag(eq(project("prj1")), any(), eq("routing"), eq(tag("tag1")), eq(tag("tag2")));
    }


    @Test
    public void testGetStarredDocuments() {
        when(repository.getStarredDocuments(any())).thenReturn(asList(createDoc("doc1").build(), createDoc("doc2").build()));
        get("/api/document/starred").should().respond(200).haveType("application/json").contain("\"doc1\"").contain("\"doc2\"");
    }

    @Test
    public void test_get_document_source_with_good_mask() throws Exception {
        File txtFile = new File(temp.getRoot(), "file.txt");
        write(txtFile, "content");
        when(indexer.get("local-datashare", "docId", "root")).thenReturn(createDoc("doc").with(txtFile.toPath()).build());

        when(repository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "*.*.*.*"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(200);

        when(repository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "127.0.0.*"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(200);
    }

    @Test
    public void test_get_document_source_with_bad_mask() {
        when(indexer.get("local-datashare", "docId", "root")).thenReturn(createDoc("doc").build());

        when(repository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "1.2.3.4"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(403);

        when(repository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "127.0.1.*"));
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(403);
    }

    @Test
    public void test_get_document_source_with_unknown_project() throws IOException {
        File txtFile = new File(temp.getRoot(), "file.txt");
        write(txtFile, "content");
        when(indexer.get("local-datashare", "docId", "root")).thenReturn(createDoc("doc").with(txtFile.toPath()).build());

        when(repository.getProject("local-datashare")).thenReturn(null);
        get("/api/local-datashare/documents/src/docId?routing=root").should().respond(200);
    }

    private void indexFile(String index, String _id, Path path, String contentType, String routing) {
        Document doc = DocumentBuilder.createDoc(_id).with(path).ofMimeType(contentType).withRootId(routing).build();
        if (routing == null) {
            when(indexer.get(index, _id)).thenReturn(doc);
        } else {
            when(indexer.get(index, _id, routing)).thenReturn(doc);
        }
    }

    static void write(File file, String content) throws IOException {
        file.toPath().getParent().toFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(UTF_8));
    }

    @Override
    public int port() { return server.port();}
}
