package org.icij.datashare.text.structure;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.parser.html.IdentityHtmlMapper;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a document's source bytes into a deterministic per-page Markdown rendering of its structure.
 * Pipeline: Tika XHTML (un-flatten) -> split on <div class="page"> -> per-page sanitize -> flexmark html2md.
 * OCR is disabled so the same bytes always yield byte-identical Markdown (the content-addressed cache
 * relies on this).
 */
public class StructureMarkdownExtractor {

    // Relaxed safelist minus <u>: keeps formatting/headings/links/lists/tables, and jsoup's Cleaner
    // strips <script>/<style>, event-handler (on*) attributes, and unsafe URL schemes (javascript:,
    // data:). Because toXhtml uses IdentityHtmlMapper (so inline formatting survives Tika), this is the
    // producer's guaranteed safety boundary rather than relying on flexmark incidentally dropping
    // dangerous markup. <u>/<ins> are not safelisted, so they are unwrapped to plain text (avoiding
    // flexmark's non-standard "++text++").
    private static final Safelist SAFELIST = Safelist.relaxed().removeTags("u");

    /**
     * Converts {@code source} into one Markdown string per page (a single entry for non-paginated
     * formats). The caller owns {@code source}: this method reads but does not close it.
     */
    public List<String> extractPages(InputStream source, String contentType)
            throws IOException, SAXException, TikaException {
        String xhtml = toXhtml(source, contentType);
        org.jsoup.nodes.Document doc = Jsoup.parse(xhtml);
        // Tika's PDF parser wraps each page in <div class="page">; that div survives ToXMLContentHandler,
        // giving one element per source page. Formats without pagination emit none, so the whole body is
        // treated as a single page.
        Elements pageDivs = doc.select("div.page");
        List<Element> pages = new ArrayList<>();
        if (pageDivs.isEmpty()) {
            pages.add(doc.body() != null ? doc.body() : doc);
        } else {
            pages.addAll(pageDivs);
        }
        List<String> markdown = new ArrayList<>();
        for (Element page : pages) {
            markdown.add(toMarkdown(sanitize(page.html())).strip());
        }
        return markdown;
    }

    String toXhtml(InputStream source, String contentType) throws IOException, SAXException, TikaException {
        ToXMLContentHandler handler = new ToXMLContentHandler();
        Metadata metadata = new Metadata();
        if (contentType != null) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdfConfig);
        // Tika's DefaultHtmlMapper drops inline formatting (<strong>, <em>, <u>, ...) when parsing HTML.
        // IdentityHtmlMapper keeps every element so bold survives to flexmark (rendered as "**bold**")
        // and underline survives to sanitize() (unwrapped to plain text); affects HTML parsing only.
        context.set(HtmlMapper.class, new IdentityHtmlMapper());
        new AutoDetectParser().parse(source, handler, metadata, context);
        return handler.toString();
    }

    /**
     * Sanitizes one page's HTML to a safe subset before Markdown conversion (see SAFELIST). Also drops
     * empty <p> blocks, which Tika emits and which flexmark would otherwise render as literal "<br />".
     */
    String sanitize(String html) {
        org.jsoup.nodes.Document cleaned = Jsoup.parseBodyFragment(Jsoup.clean(html, SAFELIST));
        for (Element p : cleaned.select("p")) {
            if (p.children().isEmpty() && p.text().isBlank()) {
                p.remove();
            }
        }
        return cleaned.body().html();
    }

    String toMarkdown(String html) {
        // ATX headings ("# Title", not Setext); collapse runs of blank lines. All other options stay at
        // their pinned 0.64.8 defaults to keep output deterministic.
        MutableDataSet options = new MutableDataSet();
        options.set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false);
        options.set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 1);
        return FlexmarkHtmlConverter.builder(options).build().convert(html);
    }
}
