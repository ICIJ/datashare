package org.icij.datashare.text.structure;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jsoup.Jsoup;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.fest.assertions.Assertions.assertThat;

public class StructureMarkdownExtractorTest {
    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    private ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void test_pdf_returns_one_markdown_per_page_without_br_noise() throws Exception {
        byte[] pdf = twoPagePdf();
        List<String> pages = extractor.extractPages(new ByteArrayInputStream(pdf), "application/pdf");
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).contains("page 1");
        assertThat(pages.get(1)).contains("page 2");
        assertThat(pages.get(0)).excludes("<br />");
        assertThat(pages.get(1)).excludes("<br />");
    }

    @Test
    public void test_bold_is_preserved_from_html() throws Exception {
        List<String> pages = extractor.extractPages(
                stream("<html><body><p>plain <strong>bold</strong> end</p></body></html>"), "text/html");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).contains("**bold**");
    }

    @Test
    public void test_underline_is_normalized_to_plain_text() throws Exception {
        List<String> pages = extractor.extractPages(
                stream("<html><body><p>see <u>under</u> line</p></body></html>"), "text/html");
        assertThat(pages.get(0)).contains("under");
        assertThat(pages.get(0)).excludes("++under++");
    }

    @Test
    public void test_non_paginated_html_is_single_page() throws Exception {
        List<String> pages = extractor.extractPages(
                stream("<html><body><h1>Title</h1><p>body</p></body></html>"), "text/html");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).contains("# Title");
    }

    @Test
    public void test_extract_pages_is_deterministic() throws Exception {
        String html = "<html><body><h1>Repeat</h1><p>same</p></body></html>";
        assertThat(extractor.extractPages(stream(html), "text/html"))
                .isEqualTo(extractor.extractPages(stream(html), "text/html"));
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
        assertThat(safe).excludes("<script");
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
    public void test_embedded_documents_are_not_split_into_extra_pages() throws Exception {
        byte[] eml = Files.readAllBytes(Path.of(Objects.requireNonNull(
                getClass().getResource("/docs/embedded_doc.eml")).toURI()));
        List<String> pages = extractor.extractPages(new ByteArrayInputStream(eml), "message/rfc822");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).contains("test embedded");
    }

    @Test
    public void test_extract_returns_raw_xhtml_with_page_divs() throws Exception {
        byte[] pdf = twoPagePdf();
        StructureMarkdownExtractor.StructureResult result =
                extractor.extract(new ByteArrayInputStream(pdf), "application/pdf");
        assertThat(result.xhtml()).contains("<html");
        assertThat(result.xhtml()).contains("class=\"page\"");
        assertThat(result.pages()).hasSize(2);
        assertThat(result.pages().get(0)).contains("page 1");
    }

    @Test
    public void test_extract_pages_delegates_to_extract() throws Exception {
        String html = "<html><body><h1>Title</h1><p>body</p></body></html>";
        assertThat(extractor.extractPages(stream(html), "text/html"))
                .isEqualTo(extractor.extract(stream(html), "text/html").pages());
    }

    @Test
    public void test_safelist_strips_scripts_for_serve_time_sanitization() {
        String safe = org.jsoup.Jsoup.clean(
                "<p>keep</p><script>alert('xss')</script>", StructureMarkdownExtractor.safelist());
        assertThat(safe).contains("keep");
        assertThat(safe).excludes("script");
        assertThat(safe).excludes("alert");
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
            doc.save(out);
            return out.toByteArray();
        }
    }
}
