package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.tika.exception.TikaConfigException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.utils.BuildVersions;
import org.icij.extract.extractor.Extractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class PageArtifactTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private final Project project = Project.project("prj");

    // Multi-byte UTF-8, rendered fine by Helvetica: 19 chars, 23 bytes (é and û each cost one extra
    // byte). Referenced by its known char count below, not by decoding the written bytes back,
    // since a decode of a wrongly-sliced multi-byte range is corrupted rather than merely shorter.
    private static final String ACCENTED_PAGE = "Pagé un: coût élevé";

    @Test
    public void test_type_is_page() {
        assertThat(new PageArtifact(new PropertiesProvider()).type()).isEqualTo(ArtifactType.PAGE);
    }

    @Test
    public void test_task_input_is_the_tika_pipeline_its_version_and_the_run_ocr_setting() {
        Map<String, Object> taskInput = new PageArtifact(new PropertiesProvider()).taskInput();

        assertThat(taskInput.get("pipeline")).isEqualTo("tika");
        assertThat(taskInput.get("ocr")).isEqualTo(true);
        String version = (String) taskInput.get("version");
        assertThat(version).excludes("Apache");
        assertThat(version.split("\\.").length).isEqualTo(3);
    }

    @Test
    public void test_task_input_fingerprints_extract_and_datashare_too() {
        // Tika's version does not cover the page splitting: extract-lib owns the parser set and the
        // handler that cuts the pages, and this class decides how they are stored. Without both, a
        // release that changes either leaves every existing page artifact looking current.
        Map<String, Object> taskInput = new PageArtifact(new PropertiesProvider()).taskInput();

        assertThat(taskInput.get("extract")).isEqualTo(BuildVersions.EXTRACT);
        assertThat(taskInput.get("datashare")).isEqualTo(BuildVersions.DATASHARE);
    }

    @Test
    public void test_task_input_records_ocr_off_when_the_run_disabled_it() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("ocr", "false"));

        assertThat(new PageArtifact(properties).taskInput().get("ocr")).isEqualTo(false);
        assertThat(new PageArtifact(properties).taskInput())
                .isNotEqualTo(new PageArtifact(new PropertiesProvider()).taskInput());
    }

    // A two-page PDF with real text: no tesseract needed, and each page has a distinct body so the
    // byte ranges can be checked against the page they claim to delimit. The first page is
    // multi-byte UTF-8 (Helvetica renders accents fine), so a range recorded in chars instead of
    // bytes would be caught rather than passing by ASCII coincidence.
    private Path twoPagePdf() throws IOException {
        Path pdf = dir.getRoot().toPath().resolve("report.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : List.of(ACCENTED_PAGE, "Page two text")) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private ArtifactContext rootContext(Path source) {
        return rootContext(source, "application/pdf");
    }

    private ArtifactContext rootContext(Path source, String contentType) {
        Document doc = createDoc("6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e")
                .with(source).ofContentType(contentType).build();
        return new ArtifactContext(project, doc, dir.getRoot().toPath().resolve("docdir"), mock(SourceExtractor.class));
    }

    private Path contentTxt(ArtifactContext context) {
        return ArtifactPath.pagesContent(context.docArtifactDir());
    }

    @Test
    public void test_produce_writes_one_content_file_and_nothing_else() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(Files.readString(contentTxt(context))).contains(ACCENTED_PAGE).contains("Page two text");
        assertThat(ArtifactPath.pagesDir(context.docArtifactDir()).toFile().list())
                .isEqualTo(new String[]{"content.txt"});
    }

    // The temp file is built by hand rather than with Files.createTempFile, whose restrictive
    // rw------- mode would otherwise survive the ATOMIC_MOVE onto content.txt. Compared against a
    // control file written with Files.write in the same directory, so this pins the umask default
    // rather than a hardcoded mode that a restrictive CI umask would falsify.
    @Test
    public void test_produce_writes_the_payload_with_the_directorys_default_permissions() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        new PageArtifact(new PropertiesProvider()).produce(context);

        Path control = ArtifactPath.pagesDir(context.docArtifactDir()).resolve("control");
        Files.write(control, new byte[0]);
        assertThat(Files.getPosixFilePermissions(contentTxt(context)))
                .isEqualTo(Files.getPosixFilePermissions(control));
    }

    @Test
    public void test_produce_returns_contiguous_byte_ranges_covering_the_content_file() throws Exception {
        Path pdf = twoPagePdf();
        ArtifactContext context = rootContext(pdf);

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.pages().total()).isEqualTo(2);
        assertThat(entry.pages().pagination().type()).isEqualTo("byteRanges");
        List<long[]> ranges = ((ByteRangePagination) entry.pages().pagination()).ranges();
        assertThat(ranges.get(0)[0]).isEqualTo(0);
        assertThat(ranges.get(0)[1]).isEqualTo(ranges.get(1)[0]);
        assertThat(ranges.get(1)[1]).isEqualTo(Files.size(contentTxt(context)));
        assertThat(entry.isComplete()).isFalse(); // the producer loop stamps status, not produce()
        assertThat(entry.contentType()).isNull();
        // Mutation-killing: the first page is multi-byte UTF-8, so a range recorded in chars
        // (page.length()) instead of bytes (bytes.length) would make this equal, not exceed, the
        // extracted page's own char count. Proven by mutation: reverting bytes.length to
        // page.length() in writePages makes this fail with "22 should be greater than 22".
        String firstPage;
        try (Extractor extractor = new Extractor()) {
            extractor.disableOcr();
            firstPage = extractor.extractPages(pdf).get(0);
        }
        long firstRangeByteLength = ranges.get(0)[1] - ranges.get(0)[0];
        assertThat(firstRangeByteLength).isGreaterThan((long) firstPage.length());
    }

    private static String slice(byte[] content, long[] range) {
        return new String(content, (int) range[0], (int) (range[1] - range[0]), StandardCharsets.UTF_8);
    }

    @Test
    public void test_pages_match_the_live_extractor_page_by_page() throws Exception {
        Path pdf = twoPagePdf();
        ArtifactContext context = rootContext(pdf);
        List<String> live;
        try (Extractor extractor = new Extractor()) {
            extractor.disableOcr();
            live = extractor.extractPages(pdf);
        }

        ManifestEntry entry = new PageArtifact(new PropertiesProvider(Map.of("ocr", "false"))).produce(context);

        byte[] content = Files.readAllBytes(contentTxt(context));
        List<long[]> ranges = ((ByteRangePagination) entry.pages().pagination()).ranges();
        assertThat(ranges).hasSize(live.size());
        for (int index = 0; index < live.size(); index++) {
            assertThat(slice(content, ranges.get(index))).isEqualTo(live.get(index));
        }
    }

    // Extraction level 2 = embedded, and the root path deliberately does not exist: a test that
    // passes proves the root was never parsed.
    private ArtifactContext embeddedContext(SourceExtractor sources) {
        Document doc = createDoc("1a2b96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e")
                .with(Path.of("/absent/root.eml")).ofContentType("application/pdf")
                .withExtractionLevel((short) 2).build();
        return new ArtifactContext(project, doc, dir.getRoot().toPath().resolve("docdir"), sources);
    }

    @Test
    public void test_a_document_whose_root_labels_disagree_is_read_down_the_embedded_path() throws Exception {
        // Extraction level says root, rootId says embed. isRootDocument() requires both, as
        // SourceExtractor and RawArtifact do, so this document must not be paginated from getPath():
        // that file is its container's, and pagination would cache the whole container under this
        // document's digest and stamp it complete.
        SourceExtractor sources = mock(SourceExtractor.class);
        Document doc = createDoc("1a2b96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e")
                .with(twoPagePdf()).ofContentType("application/pdf")
                .withExtractionLevel((short) 0).withRootId("another-id").build();
        ArtifactContext context = new ArtifactContext(project, doc,
                dir.getRoot().toPath().resolve("docdir"), sources);
        when(sources.getSource(project, doc)).thenThrow(new java.io.FileNotFoundException("no raw payload"));

        try {
            new PageArtifact(new PropertiesProvider()).produce(context);
            fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains(doc.getId());
        }
        assertThat(Files.exists(ArtifactPath.pagesDir(context.docArtifactDir()))).isFalse();
        verify(sources).getSource(project, doc);
    }

    @Test
    public void test_content_no_parser_can_read_is_reported_as_unreadable() throws Exception {
        // The same shape StructureArtifactTest pins: bytes indexed as a .docx that hold no zip signature
        // at all, so POI rejects the package outright and always will. extract-lib hands that back as the
        // cause of an IOException, so recognising it is what keeps the document from failing, logging at
        // ERROR and re-parsing on every run.
        Path broken = dir.getRoot().toPath().resolve("broken.docx");
        byte[] garbage = new byte[1024];
        new Random(42).nextBytes(garbage);
        Files.write(broken, garbage);
        ArtifactContext context = rootContext(broken,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        try {
            new PageArtifact(new PropertiesProvider()).produce(context);
            fail("expected an UnreadableContentException");
        } catch (UnreadableContentException expected) {
            assertThat(expected.getMessage()).contains(context.document().getId());
        }
        assertThat(Files.exists(ArtifactPath.pagesDir(context.docArtifactDir()))).isFalse();
    }

    @Test
    public void test_produce_reads_an_embedded_document_from_its_cached_raw_payload() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        Files.createDirectories(context.docArtifactDir());
        Files.copy(twoPagePdf(), context.docArtifactDir().resolve(ArtifactPath.RAW_FILE));

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.pages().total()).isEqualTo(2);
        assertThat(Files.readString(contentTxt(context))).contains(ACCENTED_PAGE);
        verifyNoInteractions(sources); // the payload was already there: no re-extraction
    }

    @Test
    public void test_produce_extracts_the_raw_payload_when_it_is_missing() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        Path pdf = twoPagePdf();
        // getSource() writes the payload into the document's artifact dir as a side effect, which is
        // what this stub reproduces.
        when(sources.getSource(project, context.document())).thenAnswer(invocation -> {
            Files.createDirectories(context.docArtifactDir());
            Files.copy(pdf, context.docArtifactDir().resolve(ArtifactPath.RAW_FILE));
            return Files.newInputStream(pdf);
        });

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.pages().total()).isEqualTo(2);
        verify(sources).getSource(project, context.document());
    }

    @Test
    public void test_produce_fails_when_an_embedded_document_has_no_raw_payload() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        when(sources.getSource(project, context.document()))
                .thenThrow(new java.io.FileNotFoundException("/absent/root.eml"));

        try {
            new PageArtifact(new PropertiesProvider()).produce(context);
            fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains(context.document().getId());
        }
        assertThat(Files.exists(ArtifactPath.pagesDir(context.docArtifactDir()))).isFalse();
        verify(sources).getSource(project, context.document());
    }

    @Test
    public void test_produce_fails_when_an_embedded_document_has_a_zero_byte_raw_payload() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        Files.createDirectories(context.docArtifactDir());
        Files.createFile(context.docArtifactDir().resolve(ArtifactPath.RAW_FILE));
        // getSource() is called since the cached payload is empty, but does not repair it: the raw
        // file stays zero bytes.
        when(sources.getSource(project, context.document()))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[0]));

        try {
            new PageArtifact(new PropertiesProvider()).produce(context);
            fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains(context.document().getId());
        }
        assertThat(Files.exists(ArtifactPath.pagesDir(context.docArtifactDir()))).isFalse();
        verify(sources).getSource(project, context.document());
    }

    @Test
    public void test_a_document_with_no_pages_is_recorded_as_empty_with_no_payload() throws Exception {
        Path txt = dir.getRoot().toPath().resolve("note.txt");
        Files.writeString(txt, "one line, no page divs");
        ArtifactContext context = rootContext(txt, "text/plain");

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
        assertThat(entry.isTerminal()).isTrue();
        assertThat(entry.pages()).isNull();
        assertThat(entry.taskInput().get("pipeline")).isEqualTo("tika");
        assertThat(Files.exists(ArtifactPath.pagesDir(context.docArtifactDir()))).isFalse();
    }

    @Test
    public void test_a_broken_configuration_ends_the_run_instead_of_failing_one_document() throws Exception {
        // It fails every document the same way, so ArtifactTask rethrows it where it rethrows an Error.
        // Catching it as one more document failure would drain the whole queue one ERROR at a time.
        PageArtifact misconfigured = new PageArtifact(new PropertiesProvider()) {
            @Override
            List<String> extractPages(Document document, Path source) {
                throw new ArtifactConfigurationException(new TikaConfigException("no parser for it"));
            }
        };

        try {
            misconfigured.produce(rootContext(twoPagePdf()));
            fail("expected an ArtifactConfigurationException");
        } catch (ArtifactConfigurationException expected) {
            assertThat(expected.getCause()).isInstanceOf(TikaConfigException.class);
        }
    }

    @Test
    public void test_a_failed_regeneration_leaves_the_previous_payload_untouched() throws Exception {
        ArtifactContext good = rootContext(twoPagePdf());
        new PageArtifact(new PropertiesProvider()).produce(good);
        byte[] previous = Files.readAllBytes(contentTxt(good));

        Path unreadable = dir.getRoot().toPath().resolve("gone.pdf");
        ArtifactContext broken = new ArtifactContext(project,
                createDoc(good.document().getId()).with(unreadable).ofContentType("application/pdf").build(),
                good.docArtifactDir(), mock(SourceExtractor.class));
        try {
            new PageArtifact(new PropertiesProvider()).produce(broken);
            fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains(good.document().getId());
        }

        assertThat(Files.readAllBytes(contentTxt(good))).isEqualTo(previous);
        assertThat(ArtifactPath.pagesDir(good.docArtifactDir()).toFile().list())
                .isEqualTo(new String[]{"content.txt"}); // no .tmp left behind
    }

    // content.txt as a non-empty directory forces the temp write to finish (there is nothing wrong
    // with the payload itself) and the move onto content.txt to fail: REPLACE_EXISTING cannot swap a
    // file in over a non-empty directory. That is the one failure point no other test reaches.
    @Test
    public void test_a_move_failure_after_a_complete_write_leaves_no_tmp_file() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());
        Path content = contentTxt(context);
        Files.createDirectories(content);
        Files.createFile(content.resolve("blocking-file"));

        try {
            new PageArtifact(new PropertiesProvider()).produce(context);
            fail("expected an ArtifactException");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains(context.document().getId());
        }

        // Asserts on the shape, not the old literal name: createTempFile-era ".tmp" names are gone,
        // but per-attempt UUID names still end in ".tmp", so this still fails if the temp file
        // survives.
        assertThat(Arrays.stream(ArtifactPath.pagesDir(context.docArtifactDir()).toFile().list())
                .anyMatch(name -> name.endsWith(".tmp"))).isFalse();
    }

    @Test
    public void test_the_written_manifest_matches_the_convention_shape() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), () -> false);

        assertThat(producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false)).isTrue();

        JsonNode manifest = JsonObjectMapper.getMapper()
                .readTree(context.docArtifactDir().resolve(ArtifactPath.MANIFEST_FILE).toFile());
        JsonNode entry = manifest.get("page");
        assertThat(entry.get("status").asText()).isEqualTo("complete");
        assertThat(entry.get("pages").get("total").asInt()).isEqualTo(2);
        assertThat(entry.get("pages").get("pagination").get("type").asText()).isEqualTo("byteRanges");
        assertThat(entry.get("pages").get("pagination").get("ranges").size()).isEqualTo(2);
        assertThat(entry.get("taskInput").get("pipeline").asText()).isEqualTo("tika");
        assertThat(entry.get("taskInput").get("ocr").asBoolean()).isTrue();
        assertThat(entry.has("contentType")).isFalse();
        assertThat(entry.has("filename")).isFalse();
        assertThat(entry.has("complete")).isFalse();
        assertThat(entry.has("terminal")).isFalse();
    }

    @Test
    public void test_a_second_run_skips_a_document_already_produced_with_the_same_task_input() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), () -> false);
        producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false);
        Files.delete(contentTxt(context)); // if the second run produced again, it would be back

        assertThat(producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false)).isTrue();

        assertThat(Files.exists(contentTxt(context))).isFalse();
    }
}
