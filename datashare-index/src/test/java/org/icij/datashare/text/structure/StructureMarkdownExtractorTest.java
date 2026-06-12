package org.icij.datashare.text.structure;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
