package org.icij.datashare.web;

import net.codestory.rest.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.MockIndexer;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.utils.DocumentSourceAccess;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.slf4j.event.Level;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactResourceTest extends AbstractProdWebServerTest {
    // 64-char digests: ArtifactPath.dir shards on the first two hex pairs.
    private static final String DIGEST = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();
    @Mock JooqRepository jooqRepository;
    @Mock Indexer indexer;
    @Mock PropertiesProvider propertiesProvider;
    MockIndexer mockIndexer;
    File artifactDir;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mockIndexer = new MockIndexer(indexer);
        artifactDir = temp.newFolder("artifacts");
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.toString()));
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));
        when(propertiesProvider.getProperties()).thenReturn(new Properties());
        when(propertiesProvider.createMerged(any())).thenCallRealMethod();
        configure(routes -> routes
                .add(new ArtifactResource(indexer, propertiesProvider,
                        new DocumentSourceAccess(jooqRepository, indexer, propertiesProvider)))
                .filter(new LocalUserFilter(new PropertiesProvider(), jooqRepository)));
    }

    // Indexes the document and returns its content-addressed artifact dir.
    private Path indexedDocDir(String digest) throws Exception {
        File file = new File(temp.getRoot(), digest + ".txt");
        MockIndexer.write(file, "content");
        mockIndexer.indexFile("local-datashare", digest, file.toPath(), "text/plain", null);
        Path dir = artifactDir.toPath().resolve("local-datashare")
                .resolve(digest.substring(0, 2)).resolve(digest.substring(2, 4)).resolve(digest);
        Files.createDirectories(dir);
        return dir;
    }

    private void writeManifest(Path docDir, String json) throws Exception {
        Files.writeString(docDir.resolve("manifest.json"), json);
    }

    // Written through ArtifactPath, never through an inline page-%04d format: a duplicated format
    // string would let writer and reader drift together and hide a naming bug from these tests.
    private void writePages(Path docDir, ArtifactType type, String extension, String... pages) throws Exception {
        Files.createDirectories(ArtifactPath.payloadDir(docDir, type));
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(ArtifactPath.payloadPage(docDir, type, page, extension), pages[page - 1]);
        }
    }

    private static String filesystemManifest(String type, int total) {
        return "{\"" + type + "\": {\"status\": \"complete\", \"taskInput\": {\"type\": \"" + type + "\", \"version\": 1},"
                + " \"pagination\": {\"type\": \"filesystem\", \"total\": " + total + "}}}";
    }

    @Test
    public void test_page_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/page/" + DIGEST).should().respond(403);
        get("/api/foo_index/artifacts/page/" + DIGEST + "/1").should().respond(403);
    }

    @Test
    public void test_page_manifest_not_found_for_unknown_document() {
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_artifact_dir_unset() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.PAGE, "txt", "page one");
        writeManifest(docDir, filesystemManifest("page", 1));
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.empty());
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
        // A misconfiguration that 404s every artifact of every document must not be silent: without
        // this line in the log it is indistinguishable from a document that has no artifacts.
        assertThat(logback.logs(Level.WARN).toString()).contains("artifactDir is unset").contains(DIGEST);
    }

    @Test
    public void test_page_manifest_not_found_without_manifest() throws Exception {
        indexedDocDir(DIGEST);
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_type_absent_from_manifest() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_entry_is_empty() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeManifest(docDir, "{\"page\": {\"status\": \"empty\", \"taskInput\": {},"
                + " \"pagination\": {\"type\": \"filesystem\", \"total\": 2}}}");
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_manifest_json_is_malformed() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeManifest(docDir, "{not valid json");
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_status_is_unknown() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeManifest(docDir, "{\"page\": {\"status\": \"bogus\", \"taskInput\": {}}}");
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_pagination_has_no_total() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.PAGE, "txt", "page one");
        // Complete and paginated, but the pagination block carries no total: Pagination.total is a
        // primitive, so it deserializes to 0, which is a malformed manifest rather than a document
        // that was processed into zero pages.
        writeManifest(docDir, "{\"page\": {\"status\": \"complete\", \"taskInput\": {},"
                + " \"pagination\": {\"type\": \"filesystem\"}}}");
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
        // The most likely real-world shape disagreement with datashare-python: 404 with an empty log
        // would be indistinguishable from "no page artifact for this document".
        assertThat(logback.logs(Level.WARN).toString()).contains("no usable page count").contains(docDir.toString());
    }

    @Test
    public void test_page_manifest_returns_the_page_count() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.PAGE, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should()
                .respond(200).haveType("application/json").contain("\"pages\":2");
    }

    @Test
    public void test_page_serves_one_page_as_plain_text() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.PAGE, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/2").should()
                .respond(200).haveType("text/plain;charset=UTF-8").contain("page two")
                // text/plain derived from an ingested document: a sniffing browser must not be
                // allowed to reinterpret it as something executable.
                .haveHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    public void test_page_serves_a_byte_range_page_like_the_filesystem_scheme() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        Files.createDirectories(ArtifactPath.payloadDir(docDir, ArtifactType.PAGE));
        Files.writeString(ArtifactPath.payloadContent(docDir, ArtifactType.PAGE, "txt"), "page onepage two");
        writeManifest(docDir, "{\"page\": {\"status\": \"complete\", \"taskInput\": {}, \"pagination\":"
                + " {\"type\": \"byteRanges\", \"total\": 2, \"byteRanges\": [[0, 8], [8, 16]]}}}");
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(200).contain("\"pages\":2");
        // Same body, type and out-of-range answer as the filesystem-scheme page 2 above: the scheme
        // is a storage detail the route must not expose.
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/2").should()
                .respond(200).haveType("text/plain;charset=UTF-8").contain("page two");
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/3").should().respond(404);
    }

    @Test
    public void test_page_out_of_range_and_non_numeric_are_not_found() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.PAGE, "txt", "page one");
        writeManifest(docDir, filesystemManifest("page", 1));
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/0").should().respond(404);
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/2").should().respond(404);
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/abc").should().respond(404);
    }

    @Test
    public void test_page_missing_on_disk_is_not_found_but_the_count_still_reports_it() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.PAGE, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 3));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().contain("\"pages\":3");
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/3").should().respond(404);
    }

    @Test
    public void test_structure_manifest_lists_the_formats_on_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one", "# two");
        writePages(docDir, ArtifactType.STRUCTURE, "xhtml", "<html><body>one</body></html>", "<html><body>two</body></html>");
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/structure/" + DIGEST).should()
                .respond(200).contain("\"pages\":2").contain("\"md\"").contain("\"xhtml\"");
    }

    @Test
    public void test_structure_manifest_omits_a_format_absent_from_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST).should()
                .respond(200).contain("\"formats\":[\"md\"]");
    }

    @Test
    public void test_structure_page_defaults_to_markdown() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one", "# two");
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/2").should()
                .respond(200).haveType("text/markdown;charset=UTF-8").contain("# two")
                .haveHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    public void test_structure_page_serves_xhtml_with_hardening_headers() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "xhtml", "<html><body>one</body></html>");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=xhtml").should()
                .respond(200)
                .haveType("application/xhtml+xml;charset=UTF-8")
                .haveHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                .haveHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    public void test_structure_page_rejects_an_unsupported_format() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=pdf").should()
                .respond(400).contain("md").contain("xhtml");
    }

    @Test
    public void test_structure_page_not_found_for_a_format_absent_on_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.STRUCTURE, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        // Same page, same manifest: only the requested format differs, so a 404 on xhtml can only
        // be caused by the missing format, not by a missing page or entry.
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=md").should().respond(200);
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=xhtml").should().respond(404);
    }

    @Test
    public void test_structure_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/structure/" + DIGEST).should().respond(403);
        get("/api/foo_index/artifacts/structure/" + DIGEST + "/1").should().respond(403);
        // Membership must gate before format validation: a non-member must not learn that "pdf"
        // is not one of the supported formats before they even learn they are not a member.
        get("/api/foo_index/artifacts/structure/" + DIGEST + "/1?format=pdf").should().respond(403);
    }

    @Test
    public void test_raw_serves_the_source_bytes_as_an_attachment() throws Exception {
        File file = new File(temp.getRoot(), "raw-source.txt");
        MockIndexer.write(file, "source bytes");
        mockIndexer.indexFile("local-datashare", DIGEST, file.toPath(), "text/plain", null);
        get("/api/local-datashare/artifacts/raw/" + DIGEST).should()
                .respond(200).contain("source bytes")
                .haveHeader("Content-Disposition", "attachment;filename=\"raw-source.txt\"");
    }

    @Test
    public void test_raw_serves_inline_when_asked() throws Exception {
        File file = new File(temp.getRoot(), "raw-inline.txt");
        MockIndexer.write(file, "source bytes");
        mockIndexer.indexFile("local-datashare", DIGEST, file.toPath(), "text/plain", null);
        Response response = get("/api/local-datashare/artifacts/raw/" + DIGEST + "?inline=true").response();
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.header("Content-Disposition")).isNull();
    }

    @Test
    public void test_raw_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/raw/" + DIGEST).should().respond(403);
    }

    @Test
    public void test_raw_forbidden_when_the_client_address_is_download_restricted() throws Exception {
        File file = new File(temp.getRoot(), "restricted.txt");
        MockIndexer.write(file, "source bytes");
        mockIndexer.indexFile("local-datashare", DIGEST, file.toPath(), "text/plain", null);
        get("/api/local-datashare/artifacts/raw/" + DIGEST).should().respond(200);
        // Granted project, but its download mask excludes the test client: the second rule of the
        // shared gate, which the membership check short-circuits in the non-member test above.
        when(jooqRepository.getProject("local-datashare")).thenReturn(new Project("local-datashare", "1.2.3.4"));
        get("/api/local-datashare/artifacts/raw/" + DIGEST).should().respond(403);
    }

    @Test
    public void test_artifacts_are_not_served_through_another_granted_projects_url() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, ArtifactType.PAGE, "txt", "page one");
        writeManifest(docDir, filesystemManifest("page", 1));
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/1").should().respond(200);
        // Member of other-project too: the payload sits on disk under its digest, so only the
        // per-project indexer lookup keeps the same URL from reaching another project's data.
        when(jooqRepository.getProjects()).thenReturn(List.of(new Project("other-project")));
        get("/api/other-project/artifacts/page/" + DIGEST).should().respond(404);
        get("/api/other-project/artifacts/page/" + DIGEST + "/1").should().respond(404);
        get("/api/other-project/artifacts/raw/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_raw_not_found_for_unknown_document() throws Exception {
        get("/api/local-datashare/artifacts/raw/" + DIGEST).should().respond(404);
        // Same route, same id, now indexed: a 200 here proves the 404 above came from the
        // document being unknown, not from the route itself being absent.
        File file = new File(temp.getRoot(), "known.txt");
        MockIndexer.write(file, "known bytes");
        mockIndexer.indexFile("local-datashare", DIGEST, file.toPath(), "text/plain", null);
        get("/api/local-datashare/artifacts/raw/" + DIGEST).should().respond(200);
    }

    @Test
    public void test_raw_serves_an_embedded_documents_bytes() throws Exception {
        // Same digest and fixture as DocumentResourceTest's embedded source-file tests: DIGEST is
        // the sha256 of the PDF embedded in embedded_doc.eml.
        String path = getClass().getResource("/docs/embedded_doc.eml").getPath();
        mockIndexer.indexFile("local-datashare", DIGEST, Paths.get(path), "application/pdf", "bar");
        get("/api/local-datashare/artifacts/raw/" + DIGEST + "?routing=bar").should()
                .respond(200).haveType("application/pdf").contain("PDF-1.3");
    }

    @Test
    public void test_raw_too_large_root_document_is_rejected() {
        // Same rule as /documents/src: the gate is shared, so this must answer 413 here too.
        Project index = new Project("local-datashare");
        Document root = DocumentBuilder.createDoc("bar").with(index).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document embedded = DocumentBuilder.createDoc(DIGEST).with(index).with(temp.getRoot().toPath().resolve("any.txt"))
                .withParentId("bar").withRootId("bar").build();
        mockIndexer.indexFile("local-datashare", root, embedded);
        get("/api/local-datashare/artifacts/raw/" + DIGEST + "?routing=bar").should().respond(413);
    }
}
