package org.icij.datashare.text.structure;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.fest.assertions.Assertions.assertThat;

public class StructureMarkdownExtractorTest {
    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    public void test_html_headings_and_links_render_as_markdown() throws Exception {
        String html = "<html><body><h1>Title</h1><p>Hello <a href=\"https://x.test\">link</a></p></body></html>";
        String md = extractor.extract(new ByteArrayInputStream(bytes(html)), "text/html");
        assertThat(md).contains("# Title");
        assertThat(md).contains("[link](https://x.test)");
    }

    // Exercise the page logic directly on synthetic XHTML rather than through extract(): extract()
    // routes through Tika, whose HTML/XHTML parser sanitises away input <div>s, so a div.page in a
    // raw (x)html source never survives to markPages. The real source of div.page wrappers is Tika's
    // PDF parser (which emits one per page), and markPages/toMarkdown/numberPages are the steps that
    // act on them, so we test that contract on the XHTML they would receive post-Tika.
    @Test
    public void test_page_divs_become_numbered_comments() {
        String xhtml = "<html><body>" +
                "<div class=\"page\"><p>one</p></div>" +
                "<div class=\"page\"><p>two</p></div>" +
                "</body></html>";
        String md = extractor.numberPages(extractor.toMarkdown(extractor.markPages(xhtml)));
        assertThat(md).contains("<!-- page 1 -->");
        assertThat(md).contains("<!-- page 2 -->");
        assertThat(md.indexOf("<!-- page 1 -->")).isLessThan(md.indexOf("<!-- page 2 -->"));
    }

    @Test
    public void test_conversion_is_deterministic() throws Exception {
        String html = "<html><body><h1>Repeatable</h1><p>same bytes same output</p></body></html>";
        String first = extractor.extract(new ByteArrayInputStream(bytes(html)), "text/html");
        String second = extractor.extract(new ByteArrayInputStream(bytes(html)), "text/html");
        assertThat(first).isEqualTo(second);
    }
}
