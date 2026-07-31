package org.icij.datashare.web;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.MockIndexer;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.DocumentSourceAccess;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactResourceTest extends AbstractProdWebServerTest {
    // 64-char digests: ArtifactPath.dir shards on the first two hex pairs.
    private static final String DIGEST = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
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

    private void writePages(Path docDir, String extension, String... pages) throws Exception {
        Path pagesDir = docDir.resolve("pages");
        Files.createDirectories(pagesDir);
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(pagesDir.resolve(String.format("page-%04d.%s", page, extension)), pages[page - 1]);
        }
    }

    private static String filesystemManifest(String type, int total) {
        return "{\"" + type + "\": {\"status\": \"complete\", \"taskInput\": {\"type\": \"" + type + "\", \"version\": 1},"
                + " \"pagination\": {\"type\": \"filesystem\", \"total\": " + total + "}}}";
    }

    @Test
    public void test_page_manifest_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/page/" + DIGEST).should().respond(403);
    }

    @Test
    public void test_page_manifest_not_found_for_unknown_document() {
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
    }

    @Test
    public void test_page_manifest_not_found_when_artifact_dir_unset() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one");
        writeManifest(docDir, filesystemManifest("page", 1));
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.empty());
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().respond(404);
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
    public void test_page_manifest_returns_the_page_count() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should()
                .respond(200).haveType("application/json").contain("\"pages\":2");
    }

    @Test
    public void test_page_serves_one_page_as_plain_text() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 2));
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/2").should()
                .respond(200).haveType("text/plain;charset=UTF-8").contain("page two");
    }

    @Test
    public void test_page_out_of_range_and_non_numeric_are_not_found() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one");
        writeManifest(docDir, filesystemManifest("page", 1));
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/0").should().respond(404);
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/2").should().respond(404);
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/abc").should().respond(404);
    }

    @Test
    public void test_page_missing_on_disk_is_not_found_but_the_count_still_reports_it() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writePages(docDir, "txt", "page one", "page two");
        writeManifest(docDir, filesystemManifest("page", 3));
        get("/api/local-datashare/artifacts/page/" + DIGEST).should().contain("\"pages\":3");
        get("/api/local-datashare/artifacts/page/" + DIGEST + "/3").should().respond(404);
    }

    private void writeStructurePages(Path docDir, String extension, String... pages) throws Exception {
        Path structureDir = docDir.resolve("structure");
        Files.createDirectories(structureDir);
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(structureDir.resolve(String.format("page-%04d.%s", page, extension)), pages[page - 1]);
        }
    }

    @Test
    public void test_structure_manifest_lists_the_formats_on_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one", "# two");
        writeStructurePages(docDir, "xhtml", "<html><body>one</body></html>", "<html><body>two</body></html>");
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/structure/" + DIGEST).should()
                .respond(200).contain("\"pages\":2").contain("\"md\"").contain("\"xhtml\"");
    }

    @Test
    public void test_structure_manifest_omits_a_format_absent_from_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST).should()
                .respond(200).contain("\"formats\":[\"md\"]");
    }

    @Test
    public void test_structure_page_defaults_to_markdown() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one", "# two");
        writeManifest(docDir, filesystemManifest("structure", 2));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/2").should()
                .respond(200).haveType("text/markdown;charset=UTF-8").contain("# two");
    }

    @Test
    public void test_structure_page_serves_xhtml_with_hardening_headers() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "xhtml", "<html><body>one</body></html>");
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
        writeStructurePages(docDir, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=pdf").should()
                .respond(400).contain("md").contain("xhtml");
    }

    @Test
    public void test_structure_page_not_found_for_a_format_absent_on_disk() throws Exception {
        Path docDir = indexedDocDir(DIGEST);
        writeStructurePages(docDir, "md", "# one");
        writeManifest(docDir, filesystemManifest("structure", 1));
        get("/api/local-datashare/artifacts/structure/" + DIGEST + "/1?format=xhtml").should().respond(404);
    }

    @Test
    public void test_structure_forbidden_for_non_member_project() {
        get("/api/foo_index/artifacts/structure/" + DIGEST).should().respond(403);
        get("/api/foo_index/artifacts/structure/" + DIGEST + "/1").should().respond(403);
    }
}
