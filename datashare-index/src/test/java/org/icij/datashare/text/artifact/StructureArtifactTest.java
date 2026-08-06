package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
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
import java.util.List;
import java.util.Random;
import java.util.UUID;
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
        // A packaging or filtering regression must fail here rather than record a placeholder that
        // freezes skip-if-current on every document forever.
        for (String key : List.of("datashare", "extract")) {
            String recorded = (String) structure.taskInput().get(key);
            assertThat(recorded).matches("\\d+\\.\\d+.*");
            assertThat(recorded).excludes("${");
        }
    }

    // Golden pin of the filenames and the exact stored bytes, to catch an UNINTENDED change to a rendering
    // that is a chain of Tika, jsoup and flexmark defaults a dependency bump quietly moves. When it fails,
    // confirm the change was intended, then update the expectation.
    @Test
    public void test_the_stored_page_set_is_pinned() throws Exception {
        new StructureArtifact().produce(contextFor(PINNED_HTML));

        assertThat(storedPageNames()).isEqualTo(
                List.of("page-1.md", "page-1.xhtml", "page-2.md", "page-2.xhtml"));
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
    public void test_produce_reports_content_no_parser_can_read_as_unreadable() throws Exception {
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
            org.junit.Assert.fail("expected an UnreadableContentException");
        } catch (UnreadableContentException expected) {
            assertThat(Files.exists(ArtifactPath.structureDir(dir.getRoot().toPath()))).isFalse();
        }
    }

    @Test
    public void test_a_broken_configuration_ends_the_run_instead_of_failing_one_document() throws Exception {
        // It fails every document the same way, so ArtifactTask rethrows it where it rethrows an Error.
        // Catching it as one more document failure would drain the whole queue one ERROR at a time.
        StructureArtifact misconfigured = new StructureArtifact() {
            @Override
            List<Page> parse(java.io.InputStream source, Document document) {
                throw new ArtifactConfigurationException(new TikaConfigException("no parser for it"));
            }
        };

        try {
            misconfigured.produce(contextFor(HTML));
            org.junit.Assert.fail("expected an ArtifactConfigurationException");
        } catch (ArtifactConfigurationException expected) {
            assertThat(expected.getCause()).isInstanceOf(TikaConfigException.class);
        }
    }

    @Test
    public void test_a_limit_failure_is_retried_and_unreadable_content_is_not() {
        // Recording no payload is terminal, so only content that is what it is forever earns it. Tika
        // raises its own limits and its zip-bomb guard as the same TikaException the content raises, and
        // that guard is a known false positive on these corpora, so a re-run must get past both.
        assertThat(StructureArtifact.isRetryable(new TikaMemoryLimitException(2_000_000, 1_000_000))).isTrue();
        // What AutoDetectParser turns a SecureContentHandler refusal into: the handler's own exception
        // class is private to Tika, so the message is all there is to recognise it by.
        assertThat(StructureArtifact.isRetryable(
                new TikaException("Zip bomb detected!", new SAXException("too deep")))).isTrue();
        assertThat(StructureArtifact.isRetryable(new TikaException("no zip signature"))).isFalse();
        assertThat(StructureArtifact.isRetryable(new SAXException("not well-formed"))).isFalse();
    }


    @Test
    public void test_a_document_that_renders_no_text_is_still_served_as_a_page_set() throws Exception {
        // With OCR off a scanned page and a standalone image render empty. The parser's output is served as
        // it comes: "no text on this page" is the answer, not a reason to record nothing. EMPTY means
        // something else here, that there is nothing at this path to serve at all.
        Document scan = createDoc("scan-id").with(Path.of("/path/to/scan.png"))
                .ofContentType("image/png").build();

        ManifestEntry entry = new StructureArtifact().produce(contextFor(blankPng(), scan));

        assertThat(entry.status()).isNull(); // the producer loop stamps complete
        assertThat(entry.pages().total()).isEqualTo(1);
        assertThat(Files.readString(page(1, "md"))).isEmpty();
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
    public void test_second_producer_run_skips_a_document_whose_payload_is_still_there() throws Exception {
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), () -> false);
        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);
        // Overwrite rather than delete: a deleted page is now a repair trigger, not proof of a skip.
        Files.writeString(page(1, "md"), "sentinel");

        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);

        assertThat(Files.readString(page(1, "md"))).isEqualTo("sentinel");
    }

    @Test
    public void test_a_re_run_repairs_pages_stranded_in_a_holding_pen() throws Exception {
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), () -> false);
        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);
        // What a failed AtomicDirectorySwap.restore leaves behind: the only copy of the pages in a holding
        // pen while the target is gone, until now recoverable only by hand (#2300).
        Path pen = dir.getRoot().toPath().resolve(".structure-" + UUID.randomUUID() + ".replaced");
        Files.move(ArtifactPath.structureDir(dir.getRoot().toPath()), pen);

        producer.run(List.of(new StructureArtifact()), contextFor(HTML), false);

        assertThat(Files.readString(page(1, "md"))).contains("# Title");
        // reclaimHoldingPens swept the stale pen on the way through, so the repair costs no extra copy.
        assertThat(Files.exists(pen)).isFalse();
    }
}
