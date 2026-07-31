package org.icij.datashare.text.artifact;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.extract.extractor.Extractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
    public void test_task_input_records_ocr_off_when_the_run_disabled_it() {
        PropertiesProvider properties = new PropertiesProvider(Map.of("ocr", "false"));

        assertThat(new PageArtifact(properties).taskInput().get("ocr")).isEqualTo(false);
        assertThat(new PageArtifact(properties).taskInput())
                .isNotEqualTo(new PageArtifact(new PropertiesProvider()).taskInput());
    }

    // A two-page PDF with real text: no tesseract needed, and each page has a distinct body so the
    // byte ranges can be checked against the page they claim to delimit.
    private Path twoPagePdf() throws IOException {
        Path pdf = dir.getRoot().toPath().resolve("report.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : List.of("Page one text", "Page two text")) {
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
        Document doc = createDoc("6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e")
                .with(source).ofContentType("application/pdf").build();
        Path docArtifactDir = dir.getRoot().toPath().resolve("docdir");
        return new ArtifactContext(project, doc, docArtifactDir, mock(SourceExtractor.class));
    }

    private Path contentTxt(ArtifactContext context) {
        return ArtifactPath.pagesContent(context.docArtifactDir());
    }

    @Test
    public void test_produce_writes_one_content_file_and_nothing_else() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(Files.readString(contentTxt(context))).contains("Page one text").contains("Page two text");
        assertThat(ArtifactPath.pagesDir(context.docArtifactDir()).toFile().list())
                .isEqualTo(new String[]{"content.txt"});
    }

    @Test
    public void test_produce_returns_contiguous_byte_ranges_covering_the_content_file() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.pages().total()).isEqualTo(2);
        assertThat(entry.pages().pagination().type()).isEqualTo("byteRanges");
        List<long[]> ranges = ((ByteRangePagination) entry.pages().pagination()).ranges();
        assertThat(ranges.get(0)[0]).isEqualTo(0);
        assertThat(ranges.get(0)[1]).isEqualTo(ranges.get(1)[0]);
        assertThat(ranges.get(1)[1]).isEqualTo(Files.size(contentTxt(context)));
        assertThat(entry.isComplete()).isFalse(); // the producer loop stamps status, not produce()
        assertThat(entry.contentType()).isNull();
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
    public void test_produce_reads_an_embedded_document_from_its_cached_raw_payload() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        ArtifactContext context = embeddedContext(sources);
        Files.createDirectories(context.docArtifactDir());
        Files.copy(twoPagePdf(), context.docArtifactDir().resolve(ArtifactPath.RAW_FILE));

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.pages().total()).isEqualTo(2);
        assertThat(Files.readString(contentTxt(context))).contains("Page one text");
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
    }
}
