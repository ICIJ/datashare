package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.AbstractList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StructureArtifactTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private static final String HTML = "<html><body><h1>Title</h1><p>body</p></body></html>";

    // Two pages (so the numbering is covered) of the markup a plain PDF does not reach: the safelist
    // (script and handler stripped, <u> unwrapped, relative link kept) and the flexmark options (ATX
    // headings, table and list rendering).
    private static final String PINNED_HTML = "<html><body>"
            + "<div class=\"page\"><h1>Title</h1>"
            + "<p>plain <strong>bold</strong> and <em>it</em> and <u>under</u></p>"
            + "<ul><li>one</li><li>two</li></ul>"
            + "<p><a href=\"page2.html\">next</a></p>"
            + "<script>alert(1)</script><p onclick=\"steal()\">clean</p></div>"
            + "<div class=\"page\"><table><tr><th>head</th></tr><tr><td>cell</td></tr></table></div>"
            + "</body></html>";

    private final Project project = Project.project("prj");
    private final Document doc = createDoc("doc-id").with(Path.of("/path/to/report.html"))
            .ofContentType("text/html").build();

    // A fresh stream per call: produce() consumes it, and the determinism test produces twice.
    private ArtifactContext contextFor(String html) throws Exception {
        return contextFor(html, dir.getRoot().toPath());
    }

    private ArtifactContext contextFor(String html, Path docArtifactDir) throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        when(sources.getSource(project, doc)).thenAnswer(invocation ->
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        return new ArtifactContext(project, doc, docArtifactDir, sources);
    }

    private ArtifactContext contextFor(byte[] bytes, Document document) throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        when(sources.getSource(project, document)).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
        return new ArtifactContext(project, document, dir.getRoot().toPath(), sources);
    }

    private Path page(int number, String extension) {
        return ArtifactPath.structurePage(dir.getRoot().toPath(), number, extension);
    }

    @Test
    public void test_type_and_task_input() {
        StructureArtifact structure = new StructureArtifact();
        assertThat(structure.type()).isEqualTo(ArtifactType.STRUCTURE);
        assertThat(structure.taskInput().get("pipeline")).isEqualTo("tika");
        String version = (String) structure.taskInput().get("version");
        assertThat(version).excludes("Apache");
        assertThat(version.split("\\.").length).isEqualTo(3);
        // The Tika version says nothing about the datashare-side rendering, and the hand-bumped
        // producerVersion it replaces was a constant someone had to remember.
        assertThat(structure.taskInput().get("producerVersion")).isNull();
        String datashare = (String) structure.taskInput().get("datashare");
        // A packaging or filtering regression must fail here rather than record a placeholder that
        // freezes skip-if-current on every document forever.
        assertThat(datashare).matches("\\d+\\.\\d+.*");
        assertThat(datashare).excludes("${");
    }

    // Golden pin of the filenames and the exact stored bytes, to catch an UNINTENDED change to a rendering
    // that is a chain of Tika, jsoup and flexmark defaults a dependency bump quietly moves. When it fails,
    // confirm the change was intended, then update the expectation.
    @Test
    public void test_the_stored_page_set_is_pinned() throws Exception {
        new StructureArtifact().produce(contextFor(PINNED_HTML));

        assertThat(storedPageNames()).isEqualTo(
                List.of("page-0001.md", "page-0001.xhtml", "page-0002.md", "page-0002.xhtml"));
        // The whitespace between blocks is Tika's own rendering, kept verbatim (see COMPACT_OUTPUT), so
        // these bytes track the Tika version rather than jsoup's pretty-printing defaults.
        assertThat(readPage(1, "xhtml")).isEqualTo("<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head></head><body><h1>Title</h1>\n"
                + "<p>plain <strong>bold</strong> and <em>it</em> and under</p>\n"
                + "<ul>\t<li>one</li>\n\t<li>two</li>\n</ul>\n"
                + "<p><a href=\"page2.html\">next</a></p>\n\n<p>clean</p>\n</body></html>");
        assertThat(readPage(1, "md")).isEqualTo("# Title\n\nplain **bold** and *it* and under\n\n"
                + "* one\n* two\n\n[next](page2.html)\n\nclean");
        assertThat(readPage(2, "xhtml")).isEqualTo("<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head></head><body><table><tbody><tr>\t<th>head</th></tr>\n"
                + "<tr>\t<td>cell</td></tr>\n</tbody></table>\n\n\n</body></html>");
        assertThat(readPage(2, "md")).isEqualTo("| head |\n|------|\n| cell |");
    }

    private List<String> storedPageNames() throws Exception {
        try (Stream<Path> entries = Files.list(ArtifactPath.structureDir(dir.getRoot().toPath()))) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    // Read as UTF-8 explicitly: the bytes on disk are what is pinned, not what this JVM's default
    // charset happens to make of them.
    private String readPage(int number, String extension) throws Exception {
        return new String(Files.readAllBytes(page(number, extension)), StandardCharsets.UTF_8);
    }

    @Test
    public void test_produce_writes_both_formats_for_page_one() throws Exception {
        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.readString(page(1, "md"))).contains("# Title");
        assertThat(Files.readString(page(1, "xhtml"))).contains("<h1>Title</h1>");
        assertThat(Files.exists(page(2, "md"))).isFalse();
    }

    @Test
    public void test_produce_returns_a_filesystem_paginated_entry() throws Exception {
        ManifestEntry entry = new StructureArtifact().produce(contextFor(HTML));

        assertThat(entry.pages().total()).isEqualTo(1);
        assertThat(entry.pages().pagination().type()).isEqualTo("filesystem");
        assertThat(entry.taskInput().get("pipeline")).isEqualTo("tika");
        assertThat(entry.contentType()).isNull();
        assertThat(entry.isComplete()).isFalse(); // the producer loop stamps status, not produce()
    }

    @Test
    public void test_produce_is_byte_deterministic() throws Exception {
        new StructureArtifact().produce(contextFor(HTML));
        byte[] firstMd = Files.readAllBytes(page(1, "md"));
        byte[] firstXhtml = Files.readAllBytes(page(1, "xhtml"));

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.readAllBytes(page(1, "md"))).isEqualTo(firstMd);
        assertThat(Files.readAllBytes(page(1, "xhtml"))).isEqualTo(firstXhtml);
    }

    @Test
    public void test_produce_replaces_the_flat_structure_file_written_by_the_python_producer() throws Exception {
        Files.writeString(dir.getRoot().toPath().resolve(ArtifactPath.STRUCTURE_DIR), "docling markdown");

        ManifestEntry entry = new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.isDirectory(ArtifactPath.structureDir(dir.getRoot().toPath()))).isTrue();
        assertThat(Files.readString(page(1, "md"))).contains("# Title");
        assertThat(entry.pages().total()).isEqualTo(1);
    }

    @Test
    public void test_produce_removes_stale_pages_from_a_previous_longer_run() throws Exception {
        Files.createDirectories(ArtifactPath.structureDir(dir.getRoot().toPath()));
        Files.writeString(page(9, "md"), "stale page nine");

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.exists(page(9, "md"))).isFalse();
        assertThat(Files.exists(page(1, "md"))).isTrue();
    }

    @Test
    public void test_produce_leaves_sibling_artifacts_alone() throws Exception {
        Files.writeString(dir.getRoot().toPath().resolve(ArtifactPath.RAW_FILE), "raw bytes");

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.readString(dir.getRoot().toPath().resolve(ArtifactPath.RAW_FILE))).isEqualTo("raw bytes");
    }

    @Test
    public void test_produce_fails_and_writes_nothing_when_the_source_cannot_be_read() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        when(sources.getSource(project, doc)).thenThrow(new java.io.FileNotFoundException("gone"));

        try {
            new StructureArtifact().produce(new ArtifactContext(project, doc, dir.getRoot().toPath(), sources));
            org.junit.Assert.fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            // the directory must not be created before the source is known to be readable
        }
        assertThat(Files.exists(ArtifactPath.structureDir(dir.getRoot().toPath()))).isFalse();
    }

    @Test
    public void test_produce_reports_content_no_parser_can_read_as_unparseable() throws Exception {
        // The shape a real corpus produces (NastyCorpus pins the same one): bytes indexed as a .docx that
        // hold no zip signature at all, so POI rejects the package outright and always will.
        byte[] garbage = new byte[1024];
        new Random(42).nextBytes(garbage);
        Document broken = createDoc("broken-id").with(Path.of("/path/to/broken.docx"))
                .ofContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document").build();
        SourceExtractor sources = mock(SourceExtractor.class);
        when(sources.getSource(project, broken)).thenAnswer(invocation -> new ByteArrayInputStream(garbage));

        try {
            new StructureArtifact().produce(new ArtifactContext(project, broken, dir.getRoot().toPath(), sources));
            org.junit.Assert.fail("expected an UnparseableContentException");
        } catch (UnparseableContentException expected) {
            assertThat(Files.exists(ArtifactPath.structureDir(dir.getRoot().toPath()))).isFalse();
        }
    }

    @Test
    public void test_a_limit_or_configuration_failure_is_not_recorded_as_unreadable_content() {
        // Recording no payload is terminal, so only content that is what it is forever earns it. Tika
        // raises its own limits, its configuration and its zip-bomb guard as the same TikaException, and
        // that guard is a known false positive on these corpora, so a re-run must get past all three.
        assertThat(StructureArtifact.canNeverParse(new TikaException("no zip signature"))).isTrue();
        assertThat(StructureArtifact.canNeverParse(new SAXException("not well-formed"))).isTrue();
        assertThat(StructureArtifact.canNeverParse(new TikaConfigException("no parser for it"))).isFalse();
        assertThat(StructureArtifact.canNeverParse(new TikaMemoryLimitException(2_000_000, 1_000_000))).isFalse();
        // What AutoDetectParser turns a SecureContentHandler refusal into: the handler's own exception
        // class is private to Tika, so the message is all there is to recognise it by.
        assertThat(StructureArtifact.canNeverParse(
                new TikaException("Zip bomb detected!", new SAXException("too deep")))).isFalse();
    }

    @Test
    public void test_a_document_that_renders_no_text_records_no_payload_instead_of_blank_pages() throws Exception {
        // With OCR off every scanned page and standalone image renders empty, and a complete entry over
        // blank pages leaves a consumer unable to tell "no structure" from "page one is blank".
        Document scan = createDoc("scan-id").with(Path.of("/path/to/scan.png"))
                .ofContentType("image/png").build();

        ManifestEntry entry = new StructureArtifact().produce(contextFor(blankPng(), scan));

        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
        assertThat(entry.pages()).isNull();
        assertThat(Files.exists(ArtifactPath.structureDir(dir.getRoot().toPath()))).isFalse();
    }

    @Test
    public void test_recording_no_payload_removes_the_pages_a_previous_run_left() throws Exception {
        // Otherwise the manifest says there is nothing to serve while a reader listing the directory still
        // finds the stale pages.
        Files.createDirectories(ArtifactPath.structureDir(dir.getRoot().toPath()));
        Files.writeString(page(1, "md"), "pages from a previous release");
        Document scan = createDoc("scan-id").with(Path.of("/path/to/scan.png"))
                .ofContentType("image/png").build();

        new StructureArtifact().produce(contextFor(blankPng(), scan));

        assertThat(Files.exists(ArtifactPath.structureDir(dir.getRoot().toPath()))).isFalse();
    }

    @Test
    public void test_produce_reclaims_a_replaced_payload_a_previous_run_could_not_delete() throws Exception {
        // The holding pen is named uniquely per invocation, so nothing else would reclaim one a failed
        // delete left behind: the document would grow by a full page set on every re-produce.
        Path leftover = dir.getRoot().toPath().resolve(".structure-0dd0dd.replaced");
        Files.createDirectories(leftover);
        Files.writeString(leftover.resolve("page-0001.md"), "a page set nothing reads");

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(temporaryEntries()).isEmpty();
        assertThat(Files.readString(page(1, "md"))).contains("# Title");
    }

    private static byte[] blankPng() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    public void test_produce_creates_the_document_artifact_directory_when_it_is_missing() throws Exception {
        // With raw out of the selection nothing else creates the content-addressed document dir, so
        // an ARTIFACT run over a fresh artifactDir has to create it here or fail on every document.
        Path docArtifactDir = dir.getRoot().toPath().resolve("6a/bb/6abbdigest");

        ManifestEntry entry = new StructureArtifact().produce(contextFor(HTML, docArtifactDir));

        assertThat(entry.pages().total()).isEqualTo(1);
        assertThat(Files.readString(ArtifactPath.structurePage(docArtifactDir, 1, "md"))).contains("# Title");
    }

    @Test
    public void test_produce_swaps_in_the_new_pages_when_the_previous_payload_cannot_be_deleted() throws Exception {
        // A nested, separately-locked subdir makes the previous payload undeletable while structureDir
        // itself stays writable, ruling out a pass that only means page-0001 could not be written. Relies
        // on the test process not running as root, which ignores the unwritable parent (CI and the devenv
        // both run non-root).
        Path lockedSubdir = ArtifactPath.structureDir(dir.getRoot().toPath()).resolve("locked");
        Files.createDirectories(lockedSubdir);
        Files.writeString(page(1, "md"), "old content");
        Files.writeString(lockedSubdir.resolve("leftover.md"), "stale page from a previous run");
        // Deleting a directory entry needs write permission on its parent, not the entry itself.
        lockedSubdir.toFile().setWritable(false);

        try {
            ManifestEntry entry = new StructureArtifact().produce(contextFor(HTML));

            // The previous payload is renamed aside, not deleted, so an undeletable leftover cannot fail
            // the document.
            assertThat(entry.pages().total()).isEqualTo(1);
            assertThat(Files.readString(page(1, "md"))).contains("# Title");
            assertThat(Files.exists(ArtifactPath.structureDir(dir.getRoot().toPath()).resolve("locked"))).isFalse();
        } finally {
            // Not lockedSubdir: produce() renamed the payload holding it aside, so a chmod of the original
            // path silently does nothing and leaves a tree TemporaryFolder cannot delete.
            restoreWritePermissions(dir.getRoot().toPath());
        }
    }

    private static void restoreWritePermissions(Path root) throws Exception {
        try (Stream<Path> entries = Files.walk(root)) {
            entries.filter(Files::isDirectory).forEach(entry -> entry.toFile().setWritable(true));
        }
    }

    @Test
    public void test_produce_leaves_no_temp_dir_behind_after_a_successful_run() throws Exception {
        new StructureArtifact().produce(contextFor(HTML));

        assertThat(temporaryEntries()).isEmpty();
    }

    @Test
    public void test_produce_ignores_a_leftover_of_the_old_shared_temp_directory_name() throws Exception {
        // The temp dir is unique per invocation now, so nothing at a shared path can be adopted,
        // blocked on, or destroyed by a concurrent producer of the same digest.
        Files.writeString(dir.getRoot().toPath().resolve("structure.tmp"), "leftover of a fixed name");

        ManifestEntry entry = new StructureArtifact().produce(contextFor(HTML));

        assertThat(entry.pages().total()).isEqualTo(1);
        assertThat(Files.readString(dir.getRoot().toPath().resolve("structure.tmp")))
                .isEqualTo("leftover of a fixed name");
    }

    @Test
    public void test_produce_keeps_the_old_page_set_when_the_new_one_cannot_be_written() throws Exception {
        Files.createDirectories(ArtifactPath.structureDir(dir.getRoot().toPath()));
        Files.writeString(page(1, "md"), "old content");
        // An unwritable document dir: no temp dir can be created in it, so produce() fails before it
        // touches the old, already-complete page set.
        dir.getRoot().setWritable(false);

        try {
            new StructureArtifact().produce(contextFor(HTML));
            org.junit.Assert.fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            // the new page set never made it to disk: the old one must survive untouched
        } finally {
            dir.getRoot().setWritable(true);
        }
        assertThat(Files.readString(page(1, "md"))).isEqualTo("old content");
    }

    @Test
    public void test_the_page_directory_is_as_readable_as_the_dirs_around_it() throws Exception {
        // The staging directory is renamed into place, so its mode is the mode structure/ ships with, and
        // on a shared artifactDir an owner-only page directory is EACCES for every other uid.
        new StructureArtifact().produce(contextFor(HTML));

        Path createdThePlainWay = Files.createDirectory(dir.getRoot().toPath().resolve("control"));
        assertThat(modeOf(ArtifactPath.structureDir(dir.getRoot().toPath()))).isEqualTo(modeOf(createdThePlainWay));
    }

    @Test
    public void test_produce_leaves_no_replaced_payload_behind() throws Exception {
        // The payload a run replaces is renamed aside rather than deleted, and leaving that aside behind
        // doubles the disk a re-produced document costs.
        Files.createDirectories(ArtifactPath.structureDir(dir.getRoot().toPath()));
        Files.writeString(page(1, "md"), "the payload being replaced");

        new StructureArtifact().produce(contextFor(HTML));

        assertThat(Files.readString(page(1, "md"))).contains("# Title");
        assertThat(temporaryEntries()).isEmpty();
    }

    @Test
    public void test_no_staging_directory_survives_an_error_while_writing_pages() throws Exception {
        // An OutOfMemoryError mid-write is a documented failure mode for these corpora, and the
        // staging name is unique per invocation: what it leaves behind is never reclaimed by anything.
        try {
            StructureArtifact.writePages(dir.getRoot().toPath(), pagesFailingWith(new OutOfMemoryError("boom")));
            org.junit.Assert.fail("expected the error to propagate");
        } catch (OutOfMemoryError expected) {
            // the cause the operator needs to see must not be replaced by a cleanup failure
        }
        assertThat(temporaryEntries()).isEmpty();
    }

    private static String modeOf(Path directory) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(directory));
    }

    // A page list that throws when the writing loop reaches its first page.
    private static List<Page> pagesFailingWith(Error failure) {
        return new AbstractList<>() {
            @Override public Page get(int index) { throw failure; }
            @Override public int size() { return 1; }
        };
    }

    private List<Path> temporaryEntries() throws Exception {
        try (Stream<Path> entries = Files.list(dir.getRoot().toPath())) {
            return entries.filter(entry -> entry.getFileName().toString().startsWith(".structure-")).toList();
        }
    }

    // The two cases below go through the real producer loop, which owns the manifest write and
    // skip-if-current. ArtifactProducer and FilesystemManifestRepository are in this package.

    @Test
    public void test_manifest_written_by_the_real_producer_loop_matches_the_convention() throws Exception {
        boolean produced = new ArtifactProducer(new FilesystemManifestRepository(), () -> false)
                .run(List.of(new StructureArtifact()), contextFor(HTML), false);

        assertThat(produced).isTrue();
        // The repository pretty-prints the manifest, so assert on the parsed tree rather than raw bytes.
        JsonNode structure = JsonObjectMapper.getMapper()
                .readTree(Files.readString(dir.getRoot().toPath().resolve(ArtifactPath.MANIFEST_FILE)))
                .get("structure");
        assertThat(structure.get("status").asText()).isEqualTo("complete");
        assertThat(structure.get("pages").get("total").asInt()).isEqualTo(1);
        assertThat(structure.get("pages").get("pagination").get("type").asText()).isEqualTo("filesystem");
        assertThat(structure.get("taskInput").get("pipeline").asText()).isEqualTo("tika");
    }

    @Test
    public void test_second_producer_run_skips_a_document_that_is_already_current() throws Exception {
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), () -> false);
        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);
        // Delete the payload: if the second run regenerated, the page would come back.
        Files.delete(page(1, "md"));

        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);

        assertThat(Files.exists(page(1, "md"))).isFalse();
    }
}
