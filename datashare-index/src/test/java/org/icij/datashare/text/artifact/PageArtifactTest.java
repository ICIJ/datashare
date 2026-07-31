package org.icij.datashare.text.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.extractor.DocumentSelector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
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
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.apache.tika.metadata.TikaCoreProperties.EmbeddedResourceType.ATTACHMENT;
import static org.apache.tika.metadata.TikaCoreProperties.EmbeddedResourceType.INLINE;
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
    // The mail's own text, in the fixture that carries an attachment.
    private static final String MAIL_BODY = "Mail body text.";

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

    // A mail carrying the two-page PDF above as an attachment. The attachment is a document of its own
    // (Tika marks it ATTACHMENT, extract-lib spawns it, indexes it separately and gives it its own page
    // artifact), so none of its text belongs to the mail. A mail, not a PDF holding a PDF: a PDF's own
    // page div is closed before its attachments are parsed, while a mail's is still open, which is what
    // lets an attachment's characters land in it.
    private Path emlWithAttachedPdf() throws IOException {
        Path eml = dir.getRoot().toPath().resolve("mail.eml");
        String attachment = Base64.getMimeEncoder().encodeToString(Files.readAllBytes(twoPagePdf()));
        Files.writeString(eml, String.join("\r\n",
                "From: sender@example.org",
                "To: recipient@example.org",
                "Subject: Report attached",
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"BOUND\"",
                "",
                "--BOUND",
                "Content-Type: text/plain; charset=UTF-8",
                "",
                MAIL_BODY,
                "",
                "--BOUND",
                "Content-Type: application/pdf; name=\"report.pdf\"",
                "Content-Disposition: attachment; filename=\"report.pdf\"",
                "Content-Transfer-Encoding: base64",
                "",
                attachment,
                "--BOUND--",
                ""));
        return eml;
    }

    // What indexing puts in the ES content field for a document: the text extract-lib hands the spewer,
    // which is the root's own body, since a spawned embed's text goes to its own document's reader.
    private static String indexedContent(Path source) throws IOException {
        try (Extractor extractor = new Extractor()) {
            extractor.disableOcr();
            StringWriter indexed = new StringWriter();
            try (Reader reader = extractor.extract(source).getReader()) {
                reader.transferTo(indexed);
            }
            return indexed.toString();
        }
    }

    // Page boundaries fall on whitespace Tika emits around the page divs, so the two sides can differ
    // by a newline without differing by a word. Compared on words: what must not differ is the text.
    private static String words(String text) {
        return text.replaceAll("\\s+", " ").strip();
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
        return ArtifactPath.payloadContent(context.docArtifactDir(), ArtifactType.PAGE, "txt");
    }

    @Test
    public void test_produce_writes_one_content_file_and_nothing_else() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(Files.readString(contentTxt(context))).contains(ACCENTED_PAGE).contains("Page two text");
        assertThat(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE).toFile().list())
                .isEqualTo(new String[]{"content.txt"});
    }

    // The payload must carry the umask default every other artifact file gets, not the rw-------
    // the JDK stamps on a temp file (see AtomicDirectorySwap#createStagingDir). Compared against a
    // control file written with Files.write in the same directory, so this pins the umask default
    // rather than a hardcoded mode that a restrictive CI umask would falsify.
    @Test
    public void test_produce_writes_the_payload_with_the_directorys_default_permissions() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());

        new PageArtifact(new PropertiesProvider()).produce(context);

        Path control = ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE).resolve("control");
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
        assertThat(Files.exists(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE))).isFalse();
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
        assertThat(Files.exists(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE))).isFalse();
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
        assertThat(Files.exists(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE))).isFalse();
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
        assertThat(Files.exists(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE))).isFalse();
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
        assertThat(Files.exists(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE))).isFalse();
    }

    @Test
    public void test_a_document_with_no_pages_discards_the_payload_an_earlier_run_left() throws Exception {
        // Same document, produced twice: a version bump makes the first run's entry stale and the second
        // run finds no pages. An EMPTY entry records no ranges, so a content.txt surviving it is served by
        // a reader that lists the directory with nothing left to say how to read it.
        new PageArtifact(new PropertiesProvider()).produce(rootContext(twoPagePdf()));
        Path txt = dir.getRoot().toPath().resolve("note.txt");
        Files.writeString(txt, "one line, no page divs");
        ArtifactContext context = rootContext(txt, "text/plain");

        ManifestEntry entry = new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
        assertThat(Files.exists(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE))).isFalse();
    }

    @Test
    public void test_unreadable_content_discards_the_payload_an_earlier_run_left() throws Exception {
        // The producer records this one as an empty entry too, so the same rule applies to it.
        new PageArtifact(new PropertiesProvider()).produce(rootContext(twoPagePdf()));
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
            assertThat(Files.exists(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE))).isFalse();
        }
    }

    @Test
    public void test_a_containers_pages_hold_the_same_text_as_the_container_document() throws Exception {
        // What the pages are for: the document's indexed content, paginated. An attachment is indexed as
        // its own document and paginated as its own artifact, so its text is in neither the container's
        // content nor the container's pages. Asserted against what indexing puts in the content field
        // rather than against a literal, since matching that field is the whole contract.
        Path mail = emlWithAttachedPdf();
        ArtifactContext context = rootContext(mail, "message/rfc822");
        String indexed = indexedContent(mail);

        ManifestEntry entry = new PageArtifact(new PropertiesProvider(Map.of("ocr", "false"))).produce(context);

        assertThat(words(indexed)).contains(MAIL_BODY).excludes("Page two text");
        assertThat(entry.pages().total()).isEqualTo(1);
        assertThat(words(Files.readString(contentTxt(context)))).isEqualTo(words(indexed));
    }

    private static Metadata part(String resourceName, String embeddedResourceType, String contentType) {
        Metadata metadata = new Metadata();
        if (resourceName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, resourceName);
        }
        if (embeddedResourceType != null) {
            metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE, embeddedResourceType);
        }
        if (contentType != null) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        return metadata;
    }

    @Test
    public void test_the_selection_keeps_the_document_and_its_inline_parts_and_drops_the_rest() {
        // The file being parsed has to be selected or the page-splitting handler is never used for it
        // (ParsingReaderWithContentHandler hands it a plain handler instead) and the document ends up
        // with no pages at all. A scanned page's image is INLINE and its OCR text is in the content
        // field, so it stays. A mail's own body can arrive nameless, so it stays. An attachment, a zip
        // entry and a nameless PST message are documents of their own, indexed and paginated
        // separately, so they go.
        DocumentSelector selection = PageArtifact.ownTextOf(Path.of("/corpus/mail.eml"));

        assertThat(selection.select(part("mail.eml", null, "message/rfc822"))).isTrue();
        assertThat(selection.select(part(null, null, "text/plain; charset=UTF-8"))).isTrue();
        assertThat(selection.select(part("image0.png", INLINE.toString(), "image/png"))).isTrue();
        assertThat(selection.select(part("report.pdf", ATTACHMENT.toString(), "application/pdf"))).isFalse();
        assertThat(selection.select(part("entry.txt", null, "text/plain"))).isFalse();
        assertThat(selection.select(part(null, null, "message/rfc822"))).isFalse();
    }

    @Test
    public void test_task_input_records_the_ocr_that_paginated_the_document() throws Exception {
        // The run's flag alone is not what made these pages: a document indexed without OCR is
        // paginated without OCR whatever the run asks for. Stamping those pages ocr:true is what lets
        // the same file, re-indexed with OCR on, keep its OCR-free pages forever: same bytes, same
        // digest, same artifact dir, same fingerprint, so skip-if-current never looks at it again.
        PageArtifact withOcrRun = new PageArtifact(new PropertiesProvider());
        Document notOcred = rootContext(twoPagePdf()).document();
        Document ocred = createDoc(notOcred.getId()).with(notOcred.getPath()).ofContentType("application/pdf")
                .withOcrParser("org.icij.extract.parser.ocr.OCRParserAdapter").build();

        assertThat(withOcrRun.taskInput(notOcred).get("ocr")).isEqualTo(false);
        assertThat(withOcrRun.taskInput(ocred).get("ocr")).isEqualTo(true);
        // The run's flag still counts: with OCR off the extractor has it off, whatever the document says.
        assertThat(new PageArtifact(new PropertiesProvider(Map.of("ocr", "false"))).taskInput(ocred).get("ocr"))
                .isEqualTo(false);
    }

    @Test
    public void test_a_document_reindexed_with_ocr_is_paginated_again() throws Exception {
        Path pdf = twoPagePdf();
        ArtifactContext context = rootContext(pdf);
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), () -> false);
        producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false);
        Files.delete(contentTxt(context)); // regenerated only if the second run does not skip
        Document reindexedWithOcr = createDoc(context.document().getId()).with(pdf).ofContentType("application/pdf")
                .withOcrParser("org.icij.extract.parser.ocr.OCRParserAdapter").build();
        ArtifactContext reindexed = new ArtifactContext(project, reindexedWithOcr, context.docArtifactDir(),
                mock(SourceExtractor.class));

        assertThat(producer.run(List.of(new PageArtifact(new PropertiesProvider())), reindexed, false)).isTrue();

        assertThat(Files.exists(contentTxt(reindexed))).isTrue();
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
    public void test_a_configuration_failure_reported_by_the_parse_thread_ends_the_run() throws Exception {
        // ParsingReaderWithContentHandler#read hands back what the parse thread threw as the direct
        // cause, so that is where a configuration this run cannot recover from shows up.
        Document document = rootContext(twoPagePdf()).document();
        IOException fromTheParser = new IOException("", new TikaConfigException("no parser for it"));

        try {
            PageArtifact.classify(document, fromTheParser);
            fail("expected an ArtifactConfigurationException");
        } catch (ArtifactConfigurationException expected) {
            assertThat(expected.getCause()).isInstanceOf(TikaConfigException.class);
        }
    }

    @Test
    public void test_a_configuration_failure_from_deeper_in_the_chain_fails_one_document() throws Exception {
        // Pagination spawns embeds into the same parse, so a TikaConfigException found further down the
        // chain is one embed's parser going wrong, not this run's configuration. Ending the run on it
        // would drain the queue on a single bad document. Retryable, not terminal: a config problem is
        // this side going wrong, and the next run can have it fixed.
        Document document = rootContext(twoPagePdf()).document();
        IOException fromAnEmbed = new IOException("", new IOException("embedded parse failed",
                new TikaConfigException("no parser for it")));

        ArtifactException classified = PageArtifact.classify(document, fromAnEmbed);

        assertThat(classified instanceof UnreadableContentException).isFalse();
        assertThat(classified.getMessage()).contains(document.getId());
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
        assertThat(ArtifactPath.payloadDir(good.docArtifactDir(), ArtifactType.PAGE).toFile().list())
                .isEqualTo(new String[]{"content.txt"}); // no .tmp left behind
    }

    // Whatever an earlier run left at the payload path, including a directory another producer wrote
    // there: the swap moves it aside rather than trying to replace a file over it.
    @Test
    public void test_produce_replaces_a_payload_path_holding_something_else() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());
        Files.createDirectories(contentTxt(context));
        Files.createFile(contentTxt(context).resolve("blocking-file"));

        new PageArtifact(new PropertiesProvider()).produce(context);

        assertThat(Files.readString(contentTxt(context))).contains("Page two text");
        assertThat(ArtifactPath.payloadDir(context.docArtifactDir(), ArtifactType.PAGE).toFile().list())
                .isEqualTo(new String[]{"content.txt"});
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
        // False although the run has OCR on: this document was indexed without it, so that is what
        // paginated it, and that is what makes the entry stale when the file is re-indexed with OCR.
        assertThat(entry.get("taskInput").get("ocr").asBoolean()).isFalse();
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
        // Overwritten, not deleted: skip-if-current re-produces a payload that left the disk
        // (ArtifactPayload#isMissing), so an absent content.txt would prove nothing about the skip.
        Files.writeString(contentTxt(context), "kept from the first run");

        assertThat(producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false)).isTrue();

        assertThat(Files.readString(contentTxt(context))).isEqualTo("kept from the first run");
    }

    @Test
    public void test_a_second_run_produces_again_when_the_page_payload_left_the_disk() throws Exception {
        ArtifactContext context = rootContext(twoPagePdf());
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository(), () -> false);
        producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false);
        Files.delete(contentTxt(context));

        assertThat(producer.run(List.of(new PageArtifact(new PropertiesProvider())), context, false)).isTrue();

        assertThat(Files.readString(contentTxt(context))).contains("Page two text");
    }
}
