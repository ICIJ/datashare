package org.icij.datashare.text.structure;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.DocumentSelector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.structure.StructureMarkdownExtractor.buildMetadata;

public class StructureMarkdownExtractorTest {
    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    // No filename hint: these cases pin what detection makes of the bytes and the content type alone.
    private List<Page> extract(InputStream source, String contentType) throws Exception {
        return extract(extractor, source, contentType);
    }

    private List<Page> extract(StructureMarkdownExtractor extractor, InputStream source, String contentType)
            throws Exception {
        return extractor.extract(source, contentType, null);
    }

    private ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> markdown(List<Page> pages) {
        return pages.stream().map(Page::markdown).toList();
    }

    @Test
    public void test_the_filename_is_passed_to_tika_as_a_detection_hint() {
        // The other hint Tika's detector takes, as deterministic as the content type next to it, and the
        // one that tells a .csv from the plain text its bytes also are.
        assertThat(buildMetadata("text/plain", "notes.csv").get(TikaCoreProperties.RESOURCE_NAME_KEY))
                .isEqualTo("notes.csv");
        assertThat(buildMetadata("text/plain", null).get(TikaCoreProperties.RESOURCE_NAME_KEY)).isNull();
        assertThat(buildMetadata("text/plain", " ").get(TikaCoreProperties.RESOURCE_NAME_KEY)).isNull();
    }

    @Test
    public void test_pdf_returns_one_page_per_pdf_page_without_br_noise() throws Exception {
        List<Page> pages = extract(new ByteArrayInputStream(twoPagePdf()), "application/pdf");
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).markdown()).contains("page 1");
        assertThat(pages.get(1).markdown()).contains("page 2");
        assertThat(pages.get(0).markdown()).excludes("<br />");
    }

    @Test
    public void test_page_xhtml_is_a_parseable_xhtml_document() throws Exception {
        Page page = extract(new ByteArrayInputStream(twoPagePdf()), "application/pdf").get(0);
        assertThat(page.xhtml()).startsWith("<html xmlns=\"http://www.w3.org/1999/xhtml\">");
        assertThat(page.xhtml()).contains("<body>");
        assertThat(page.xhtml()).contains("page 1");
        // parses under the XML parser, which a bare body fragment would not
        assertThat(Jsoup.parse(page.xhtml(), "", Parser.xmlParser()).select("body").size()).isEqualTo(1);
    }

    @Test
    public void test_page_markdown_derives_from_the_same_sanitized_html() throws Exception {
        Page page = extract(
                stream("<html><body><p>plain <strong>bold</strong><script>alert(1)</script></p></body></html>"),
                "text/html").get(0);
        assertThat(page.xhtml()).excludes("script");
        assertThat(page.xhtml()).contains("<strong>bold</strong>");
        assertThat(page.markdown()).contains("**bold**");
        assertThat(page.markdown()).excludes("alert");
    }

    // Both hints a real Markdown document carries: the content type Tika detected at INDEX time and
    // the resource name from its metadata.
    private Page markdownPage(String source) throws Exception {
        return extractor.extract(stream(source), "text/x-web-markdown", "README.md").get(0);
    }

    @Test
    public void test_a_markdown_source_is_parsed_as_markdown_not_as_literal_text() throws Exception {
        Page page = markdownPage("# Title\n\nSome **bold** and `code`.\n");

        assertThat(page.markdown()).contains("# Title");
        assertThat(page.markdown()).contains("**bold**");
        assertThat(page.markdown()).contains("`code`");
        // The bug: a Markdown source reaches the converter as one text node, so every metacharacter
        // in it comes back backslash-escaped.
        assertThat(page.markdown()).excludes("\\*");
        assertThat(page.markdown()).excludes("\\`");
        assertThat(page.xhtml()).contains("<h1>Title</h1>");
        assertThat(page.xhtml()).contains("<strong>bold</strong>");
    }

    @Test
    public void test_markdown_lists_and_links_survive_without_escaping() throws Exception {
        Page page = markdownPage("- item one\n- item two\n\nA [link](http://example.com).\n");

        assertThat(page.markdown()).contains("item one");
        assertThat(page.markdown()).contains("[link](http://example.com)");
        assertThat(page.markdown()).excludes("\\[");
        assertThat(page.xhtml()).contains("<li>item one</li>");
    }

    @Test
    public void test_unordered_lists_keep_the_dash_marker_of_the_markdown_source() throws Exception {
        Page page = markdownPage("- one\n- two\n");

        assertThat(page.markdown()).contains("- one");
        assertThat(page.markdown()).excludes("* one");
    }

    @Test
    public void test_raw_html_in_a_markdown_source_is_sanitized_out_of_both_formats() throws Exception {
        Page page = markdownPage("Real text.\n\n<script>alert(1)</script>\n");

        // Today this survives as "\<script\>alert(1)\</script\>", inert only because of the escaping
        // this change removes, so the safelist has to be what drops it.
        assertThat(page.markdown()).excludes("alert");
        assertThat(page.markdown()).excludes("script");
        assertThat(page.xhtml()).excludes("script");
        assertThat(page.markdown()).contains("Real text.");
    }

    @Test
    public void test_a_markdown_image_and_a_javascript_link_lose_their_targets() throws Exception {
        Page page = markdownPage("![shot](http://remote/x.png)\n\nA [bad](javascript:alert(1)) link.\n");

        assertThat(page.xhtml()).excludes("http://remote/x.png");
        assertThat(page.xhtml()).excludes("javascript:");
        assertThat(page.markdown()).excludes("javascript:");
        assertThat(page.markdown()).contains("bad");
    }

    @Test
    public void test_a_fenced_code_block_keeps_its_content_including_html() throws Exception {
        Page page = markdownPage("```bash\nls -l\n<script>in a fence</script>\n```\n");

        // A code sample is content, not markup: it survives as text on both sides.
        assertThat(page.markdown()).contains("ls -l");
        assertThat(page.markdown()).contains("<script>in a fence</script>");
        assertThat(page.xhtml()).contains("<pre>");
        assertThat(page.xhtml()).contains("&lt;script&gt;in a fence&lt;/script&gt;");
    }

    @Test
    public void test_a_fenced_code_block_keeps_its_language() throws Exception {
        Page page = markdownPage("```bash\nls -l\n```\n");

        assertThat(page.xhtml()).contains("class=\"language-bash\"");
        assertThat(page.markdown()).contains("```bash");
    }

    @Test
    public void test_an_unsanitized_code_class_cannot_break_out_of_the_markdown_fence() throws Exception {
        // jsoup cannot filter an attribute's value, and html2md writes a <code> class verbatim into the
        // fence info string with no re-sanitization after: a class holding a newline, a closing fence and
        // markup closes the fence early and drops the rest in as a raw HTML block.
        Page page = extract(stream(
                "<pre><code class=\"js&#10;```&#10;&lt;img src=x onerror=alert(1)&gt;&#10;\">code</code></pre>"),
                "text/html").get(0);

        assertThat(page.markdown()).excludes("onerror");
        assertThat(page.markdown()).excludes("<img");
    }

    @Test
    public void test_a_multi_class_highlighter_does_not_produce_a_garbage_fence_info_string() throws Exception {
        // A real highlighter class (hljs, sourceCode, a language name) has no "language-" prefix, so kept
        // verbatim it would produce a fence info string no renderer understands.
        Page page = extract(stream("<pre><code class=\"hljs sourceCode python\">code</code></pre>"),
                "text/html").get(0);

        assertThat(page.markdown()).excludes("hljs sourceCode python");
    }

    @Test
    public void test_a_gfm_table_in_a_markdown_source_survives_the_round_trip() throws Exception {
        Page page = markdownPage("| a | b |\n|---|---|\n| 1 | 2 |\n");

        assertThat(page.xhtml()).contains("<table>");
        assertThat(page.markdown()).contains("| a | b |");
        assertThat(page.markdown()).excludes("\\|");
    }

    @Test
    public void test_strikethrough_in_a_markdown_source_survives_as_markup() throws Exception {
        Page page = markdownPage("~~struck~~ and kept.\n");

        assertThat(page.xhtml()).contains("<del>struck</del>");
        assertThat(page.markdown()).contains("~~struck~~");
    }

    @Test
    public void test_a_thematic_break_in_a_markdown_source_survives_the_round_trip() throws Exception {
        Page page = markdownPage("a\n\n---\n\nb\n");

        assertThat(page.xhtml()).contains("<hr");
        assertThat(page.markdown()).contains("---");
    }

    @Test
    public void test_plain_text_still_escapes_markdown_metacharacters() throws Exception {
        // Not a markup language: a literal "**" in a .txt must stay literal, so the branch above it
        // must not widen to every text/* type.
        Page page = extract(stream("a **b** c"), "text/plain").get(0);

        assertThat(page.markdown()).contains("\\*");
        assertThat(page.xhtml()).excludes("<strong>");
    }

    @Test
    public void test_markdown_extraction_is_deterministic() throws Exception {
        String source = "# Title\n\n- one\n- two\n\n`code`\n";

        assertThat(markdownPage(source).markdown()).isEqualTo(markdownPage(source).markdown());
        assertThat(markdownPage(source).xhtml()).isEqualTo(markdownPage(source).xhtml());
    }

    @Test
    public void test_underline_is_normalized_to_plain_text() throws Exception {
        Page page = extract(
                stream("<html><body><p>see <u>under</u> line</p></body></html>"), "text/html").get(0);
        assertThat(page.markdown()).contains("under");
        assertThat(page.markdown()).excludes("++under++");
    }

    @Test
    public void test_non_paginated_html_is_single_page() throws Exception {
        List<Page> pages = extract(
                stream("<html><body><h1>Title</h1><p>body</p></body></html>"), "text/html");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).markdown()).contains("# Title");
    }

    // Pins the bytes Tika's paginated output renders to, to catch an UNINTENDED change. When it fails,
    // confirm the change was intended, then update the expectation.
    @Test
    public void test_pdf_page_bytes_are_pinned() throws Exception {
        List<Page> pages = extract(new ByteArrayInputStream(twoPagePdf()), "application/pdf");

        assertThat(pages).hasSize(2);
        // The whitespace between blocks is Tika's own rendering, kept verbatim, so the bytes track the Tika
        // version rather than jsoup's pretty-printing defaults.
        assertThat(pages.get(0).xhtml()).isEqualTo("<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head></head><body><p>Body on page 1.</p>\n</body></html>");
        assertThat(pages.get(0).markdown()).isEqualTo("Body on page 1.");
        assertThat(pages.get(1).xhtml()).isEqualTo("<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head></head><body><p>Body on page 2.</p>\n\n\n</body></html>");
        assertThat(pages.get(1).markdown()).isEqualTo("Body on page 2.");
    }

    @Test
    public void test_extract_is_deterministic() throws Exception {
        String html = "<html><body><h1>Repeat</h1><p>same</p></body></html>";
        List<Page> first = extract(stream(html), "text/html");
        List<Page> second = extract(stream(html), "text/html");
        assertThat(markdown(first)).isEqualTo(markdown(second));
        assertThat(first.get(0).xhtml()).isEqualTo(second.get(0).xhtml());
    }

    @Test
    public void test_sanitize_strips_scripts_handlers_and_unsafe_urls() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment(
                "<p onclick=\"steal()\">keep</p>" +
                "<script>alert('xss')</script>" +
                "<a href=\"javascript:alert(1)\">link</a>" +
                "<img src=x onerror=\"alert(1)\">").body());
        assertThat(safe).excludes("script");
        assertThat(safe).excludes("onclick");
        assertThat(safe).excludes("onerror");
        assertThat(safe).excludes("javascript:");
        assertThat(safe).excludes("alert");
        assertThat(safe).excludes("steal");
        assertThat(safe).contains("keep");
    }

    @Test
    public void test_sanitize_strips_data_url_scheme() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment(
                "<img src=\"data:image/png;base64,AAAA\">keep" +
                "<a href=\"data:text/html;base64,BBBB\">link</a>").body());
        assertThat(safe).excludes("data:");
        assertThat(safe).contains("keep");
    }

    @Test
    public void test_sanitize_drops_remote_image_sources() {
        // A stored page is rendered, so a remote reference the document's author controls is a beacon
        // reporting the reader's IP and which document they opened. A tracking pixel in an HTML mail is
        // exactly that, and it can be absolute, protocol-relative or scheme-less.
        String safe = extractor.sanitize(Jsoup.parseBodyFragment(
                "<p>keep</p>"
                + "<img src=\"https://evil.example/pixel.png\">"
                + "<img src=\"//evil.example/pixel.png\">"
                + "<img src=\"http://evil.example/pixel.png\" alt=\"logo\">").body());

        assertThat(safe).excludes("evil.example");
        assertThat(safe).contains("keep");
        // the image itself is kept (its alt is content), only the fetch is gone
        assertThat(safe).contains("alt=\"logo\"");
    }

    @Test
    public void test_sanitize_keeps_a_link_target_readable() {
        // A link is not fetched by rendering the page, and a journalist needs to see where it points.
        String safe = extractor.sanitize(Jsoup.parseBodyFragment(
                "<a href=\"https://example.com/report\">report</a>").body());

        assertThat(safe).contains("https://example.com/report");
    }

    @Test
    public void test_sanitize_injects_no_indentation_of_its_own() {
        // Pretty-printed indentation would be baked into the stored XHTML and, since the Markdown is
        // derived from the same content, become Markdown hard breaks the source never had. It would also
        // pin the content-addressed cache to jsoup's pretty-printer.
        String safe = extractor.sanitize(Jsoup.parseBodyFragment("<ul><li>one</li><li>two</li></ul>").body());

        assertThat(safe).isEqualTo("<ul><li>one</li><li>two</li></ul>");
    }

    @Test
    public void test_paragraph_with_only_br_is_dropped() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment("<p><br></p><p>real</p>").body());
        assertThat(safe).excludes("<br");
        assertThat(safe).contains("real");
    }

    @Test
    public void test_relative_links_are_preserved() {
        String safe = extractor.sanitize(Jsoup.parseBodyFragment("<a href=\"page2.html\">next</a>").body());
        assertThat(safe).contains("page2.html");
    }

    @Test
    public void test_only_a_containers_own_body_is_inlined() {
        // The exact metadata shapes Tika hands shouldParseEmbedded: a mail's own text part carries no
        // resourceName, while an attachment, a zip entry and a PST mail item are documents in their own
        // right.
        DocumentSelector selector = StructureMarkdownExtractor.buildParseContext().get(DocumentSelector.class);

        assertThat(selector.select(partMetadata(null, "text/plain"))).isTrue();
        assertThat(selector.select(partMetadata(null, "text/html; charset=UTF-8"))).isTrue();
        assertThat(selector.select(partMetadata("embedded.pdf", "application/pdf"))).isFalse();
        assertThat(selector.select(partMetadata("inner.txt", null))).isFalse();
        assertThat(selector.select(partMetadata(null, "application/x-tika-pst-mail-item"))).isFalse();
    }

    @Test
    public void test_a_mail_body_nested_in_a_multipart_related_is_inlined() throws Exception {
        // The standard Outlook shape (multipart/mixed > multipart/related > text/html). Tika's
        // MailContentHandler defaults EMBEDDED_RESOURCE_TYPE to ATTACHMENT for any part with no
        // Content-Disposition, so a disposition test here refuses the mail's own body.
        List<Page> pages = extract(stream(nestedBodyMail()), "message/rfc822");

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).markdown()).contains("the mail body text");
    }

    private String nestedBodyMail() {
        return "Subject: nested body\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"OUTER\"\r\n"
                + "\r\n"
                + "--OUTER\r\n"
                + "Content-Type: multipart/related; boundary=\"INNER\"\r\n"
                + "\r\n"
                + "--INNER\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<html><body><p>the mail body text</p></body></html>\r\n"
                + "--INNER--\r\n"
                + "--OUTER--\r\n";
    }

    private Metadata partMetadata(String resourceName, String contentType) {
        Metadata metadata = new Metadata();
        if (resourceName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, resourceName);
        }
        if (contentType != null) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        return metadata;
    }

    @Test
    public void test_embedded_documents_are_not_split_into_extra_pages() throws Exception {
        byte[] eml = Files.readAllBytes(Path.of(Objects.requireNonNull(
                getClass().getResource("/docs/embedded_doc.eml")).toURI()));
        List<Page> pages = extract(new ByteArrayInputStream(eml), "message/rfc822");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).markdown()).contains("test embedded");
    }

    @Test
    public void test_root_pages_survive_when_an_embedded_part_also_paginates() throws Exception {
        // The embedded part's page div is not a page of the root's. Its text is not dropped either (see
        // below), so it rides on the last page like any content outside the root's page divs.
        List<Page> pages = extract(stream(
                "<html><body>" +
                "<div class=\"page\"><p>root one</p></div>" +
                "<div class=\"page\"><p>root two</p></div>" +
                "<div class=\"embedded\"><div class=\"page\"><p>attachment</p></div></div>" +
                "</body></html>"), "text/html");

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).markdown()).contains("root one");
        assertThat(pages.get(0).markdown()).excludes("attachment");
        assertThat(pages.get(1).markdown()).contains("root two");
    }

    @Test
    public void test_text_inside_an_embedded_container_is_kept_wherever_the_container_sits() throws Exception {
        // What reaches this DOM inside an embedded container is the container's own inlined body or a
        // source document's literal class="embedded" markup, both the root's own text: another document's
        // content never gets here, since the DocumentSelector refuses every named part first.
        String insidePage = "<html><body><div class=\"page\"><p>root one</p>"
                + "<div class=\"embedded\"><p>inlined body</p></div></div></body></html>";
        String besidePages = "<html><body><div class=\"page\"><p>root one</p></div>"
                + "<div class=\"embedded\"><p>inlined body</p></div></body></html>";
        String withoutPages = "<html><body><div class=\"embedded\"><p>inlined body</p></div></body></html>";

        for (String markup : List.of(insidePage, besidePages, withoutPages)) {
            List<Page> pages = extract(stream(markup), "text/html");
            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).markdown()).contains("inlined body");
        }
    }

    @Test
    public void test_output_over_the_limit_stops_the_parse_instead_of_filling_the_heap() throws Exception {
        // The rendering is buffered whole and held several times over (buffer, string, DOM, every page's
        // XHTML and Markdown), times --parallelism, and no catch on the produce path handles an OOM.
        StructureMarkdownExtractor bounded = new StructureMarkdownExtractor(100);

        try {
            extract(bounded, stream("<html><body><p>" + "x".repeat(10_000) + "</p></body></html>"), "text/html");
            org.junit.Assert.fail("expected the parse to stop at the output limit");
        } catch (SAXException | TikaException expected) {
            // told apart from a readable document one level up, not silently truncated
        }
    }

    @Test
    public void test_body_content_entirely_inside_page_divs_is_unchanged() throws Exception {
        // Pinned byte-for-byte: a change here invalidates every already-produced page set.
        List<Page> pages = extract(stream(
                "<html><body><div class=\"page\"><p>A</p></div><div class=\"page\"><p>B</p></div></body></html>"),
                "text/html");

        assertThat(markdown(pages)).isEqualTo(List.of("A", "B"));
        assertThat(pages.get(0).xhtml()).isEqualTo(
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head><body><p>A</p>\n</body></html>");
    }

    @Test
    public void test_content_around_the_page_divs_joins_the_last_page_exactly_once() throws Exception {
        // Before, between and after the page divs, at any nesting depth: all kept in document order on the
        // last page, since losing document text is worse than a page boundary one node off.
        List<Page> pages = extract(stream(
                "<html><body><p>header</p><div class=\"wrapper\">" +
                "<div class=\"page\"><p>A</p></div><p>between</p><div class=\"page\"><p>B</p></div>" +
                "</div><p>footer</p></body></html>"), "text/html");

        // "header  \nbetween" is one hard break, not a lost blank line: the relaxed safelist keeps the
        // wrapper <div>, and flexmark renders a div as a paragraph by default (DIV_AS_PARAGRAPH).
        assertThat(markdown(pages)).isEqualTo(List.of("A", "B\n\nheader  \nbetween\n\nfooter"));
    }

    @Test
    public void test_a_page_div_nested_in_another_page_div_is_rendered_once() throws Exception {
        // A nested page div belongs to its parent page's rendering, not to a page of its own.
        List<Page> pages = extract(stream(
                "<html><body><div class=\"page\"><p>outer</p>" +
                "<div class=\"page\"><p>inner</p></div></div>" +
                "<div class=\"page\"><p>second</p></div></body></html>"), "text/html");

        assertThat(markdown(pages)).isEqualTo(List.of("outer  \ninner", "second"));
    }

    @Test
    public void test_a_container_root_does_not_inline_its_embedded_parts_text() throws Exception {
        // The root's XHTML must stop at its own content: inlining a mail archive's or a zip's whole
        // recursive tree buffers it in heap only for the page selection to throw it away.
        List<Page> pages = extract(new ByteArrayInputStream(zipContaining("inner.txt",
                "secret inner text")), "application/zip");

        assertThat(markdown(pages).toString()).excludes("secret inner text");
    }

    @Test
    public void test_a_pdfs_bookmarks_and_form_fields_land_on_its_last_page() throws Exception {
        // Tika appends both after the last page div. They belong to no page in particular, and the
        // page count stays the PDF's, so they ride on the last page rather than being dropped.
        List<Page> pages = extract(new ByteArrayInputStream(pdfWithOutlineAndForm()), "application/pdf");

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).markdown()).isEqualTo("Body on page 1.");
        assertThat(pages.get(1).markdown()).contains("Body on page 2.");
        assertThat(pages.get(1).markdown()).contains("OutlineEntry1");
        assertThat(pages.get(1).markdown()).contains("FormFieldValue");
    }

    @Test
    public void test_parse_context_disables_ocr_for_both_pdf_and_standalone_images() {
        ParseContext context = StructureMarkdownExtractor.buildParseContext();

        assertThat(context.get(PDFParserConfig.class).getOcrStrategy())
                .isEqualTo(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        assertThat(context.get(TesseractOCRConfig.class).isSkipOcr()).isTrue();
    }

    @Test
    public void test_generic_content_type_hint_is_ignored() throws Exception {
        // application/octet-stream is what embedded nodes often carry; passing it as a hint would
        // mislead Tika's detection, so it must be dropped and the bytes detected instead.
        List<Page> pages = extract(
                stream("<html><body><h1>Detected</h1></body></html>"), "application/octet-stream");
        assertThat(pages.get(0).markdown()).contains("# Detected");
    }

    @Test
    public void test_a_generic_content_type_hint_with_a_markdown_filename_still_takes_the_markdown_branch()
            throws Exception {
        // isMarkdown reads the type Tika detected, not the caller's hint: a mismatched or generic hint
        // (an embedded .md arriving with its container's type, for instance) must not turn the branch off.
        Page page = extractor.extract(stream("Some **bold** text.\n"), "application/octet-stream", "README.md")
                .get(0);

        assertThat(page.markdown()).contains("**bold**");
    }

    private byte[] zipContaining(String entryName, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addTwoPages(doc);
            doc.save(out);
            return out.toByteArray();
        }
    }

    // The same two pages plus the two document-level sections Tika renders outside every page div: a
    // bookmark outline and an AcroForm text field.
    private byte[] pdfWithOutlineAndForm() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addTwoPages(doc);
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(doc.getPage(p));
                PDActionGoTo action = new PDActionGoTo();
                action.setDestination(destination);
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle("OutlineEntry" + (p + 1));
                item.setAction(action);
                outline.addLast(item);
            }
            PDAcroForm acroForm = new PDAcroForm(doc);
            acroForm.setDefaultResources(new PDResources());
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDTextField field = new PDTextField(acroForm);
            field.setPartialName("FormFieldName");
            // the value straight into the COS dictionary: setValue() would need an appearance stream
            field.getCOSObject().setString(COSName.V, "FormFieldValue");
            acroForm.getFields().add(field);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void addTwoPages(PDDocument doc) throws Exception {
        for (int p = 1; p <= 2; p++) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Body on page " + p + ".");
                cs.endText();
            }
        }
    }
}
