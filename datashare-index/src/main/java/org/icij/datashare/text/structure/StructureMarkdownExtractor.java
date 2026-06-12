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
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a document's source bytes into a deterministic per-page Markdown rendering of its structure.
 * Pipeline: Tika XHTML (un-flatten) -> split on <div class="page"> -> per-page sanitize -> flexmark html2md.
 * OCR is disabled and embedded documents do not contribute their own pages, so the same bytes always
 * yield byte-identical Markdown (the content-addressed cache relies on this).
 */
public class StructureMarkdownExtractor {

    private static final String GENERIC_CONTENT_TYPE = "application/octet-stream";

    // Relaxed safelist minus <u>: keeps formatting/headings/links/lists/tables; jsoup's Cleaner strips
    // <script>/<style>, on* handlers, and unsafe URL schemes (javascript:/data:). preserveRelativeLinks
    // keeps scheme-less relative URLs (absolute javascript:/data: are still rejected by the protocol
    // allowlist). <u>/<ins> are unwrapped to plain text (avoiding flexmark's non-standard "++text++").
    private static final Safelist SAFELIST = Safelist.relaxed().removeTags("u").preserveRelativeLinks(true);

    // Stateless once built; build once rather than per page. ATX headings (not Setext) and collapsed
    // blank lines; all other options at pinned 0.64.8 defaults for deterministic output.
    private static final FlexmarkHtmlConverter MARKDOWN_CONVERTER = FlexmarkHtmlConverter.builder(
            new MutableDataSet()
                    .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                    .set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 1)).build();

    /**
     * Converts {@code source} into one Markdown string per page (a single entry for non-paginated
     * formats). The caller owns {@code source}: this method reads but does not close it.
     */
    public List<String> extractPages(InputStream source, String contentType)
            throws IOException, SAXException, TikaException {
        String xhtml = toXhtml(source, contentType);
        org.jsoup.nodes.Document doc = Jsoup.parse(xhtml);
        // Tika's PDF parser wraps each page in <div class="page">. Only count pages owned by the root
        // document: each node (the root and every embedded child) is extracted as its own artifact, so
        // the root must not absorb its children's pages. Tika nests an embedded part's output under
        // <div class="email-entry"> (mail), <div class="package-entry"> (archives), or
        // <div class="embedded"> (generic embedded objects: PDF-in-PDF, OLE objects in Office files,
        // ...); pages with such an ancestor belong to a child. This is the DOM analogue of
        // PageIndicesContentHandler's embeddedLevel==0 guard. Formats without pagination emit no
        // <div class="page">, so the whole body is treated as a single page.
        List<Element> pages = new ArrayList<>();
        for (Element pageDiv : doc.select("div.page")) {
            if (!isEmbedded(pageDiv)) {
                pages.add(pageDiv);
            }
        }
        if (pages.isEmpty()) {
            pages.add(doc.body() != null ? doc.body() : doc);
        }
        List<String> markdown = new ArrayList<>();
        for (Element page : pages) {
            markdown.add(MARKDOWN_CONVERTER.convert(sanitize(page)).strip());
        }
        return markdown;
    }

    // True when the element is nested inside an embedded child's container (mail part, archived file, or
    // generic embedded object), i.e. it does not belong to the root document.
    private static boolean isEmbedded(Element element) {
        return !element.parents().select("div.embedded, div.email-entry, div.package-entry").isEmpty();
    }

    String toXhtml(InputStream source, String contentType) throws IOException, SAXException, TikaException {
        ToXMLContentHandler handler = new ToXMLContentHandler();
        Metadata metadata = new Metadata();
        // Skip a generic application/octet-stream hint and let Tika auto-detect from the bytes; a
        // specific, non-blank hint is honoured.
        if (contentType != null && !contentType.isBlank() && !GENERIC_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
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
     * Sanitizes one page (live jsoup DOM) to a safe Markdown-ready HTML subset (see SAFELIST) and drops
     * empty paragraphs (Tika's "<br />" source: both <p/> and <p><br/></p>), while keeping a <p> that
     * holds an <img>.
     */
    String sanitize(Element page) {
        for (Element p : page.select("p")) {
            if (p.text().isBlank() && p.select("img").isEmpty()) {
                p.remove();
            }
        }
        return Jsoup.clean(page.html(), SAFELIST);
    }
}
