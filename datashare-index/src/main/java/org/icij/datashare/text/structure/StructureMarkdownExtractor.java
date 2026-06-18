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

    // Containers Tika wraps an embedded part's output in: a mail part, an archived file, or a generic
    // embedded object (PDF-in-PDF, OLE objects in Office files, ...).
    private static final String EMBEDDED_CONTAINERS = "div.embedded, div.email-entry, div.package-entry";

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

    /** The raw Tika XHTML (exactly as emitted) paired with the per-page Markdown derived from it. */
    public record StructureResult(String xhtml, List<String> pages) {}

    /** The producer-side sanitization boundary, exposed so serve-time sanitizers reuse the same rules. */
    public static Safelist safelist() {
        return SAFELIST;
    }

    /**
     * Parses {@code source} once, returning both the raw Tika XHTML (the artifact to persist) and one
     * Markdown string per page. The caller owns {@code source}: this method reads but does not close it.
     */
    public StructureResult extract(InputStream source, String contentType)
            throws IOException, SAXException, TikaException {
        String xhtml = toXhtml(source, contentType);
        org.jsoup.nodes.Document document = Jsoup.parse(xhtml);
        List<String> pagesMarkdown = new ArrayList<>();
        for (Element page : selectRootPages(document)) {
            pagesMarkdown.add(pageToMarkdown(page));
        }
        return new StructureResult(xhtml, pagesMarkdown);
    }

    /** Per-page Markdown only. Retained for callers that do not need the XHTML. */
    public List<String> extractPages(InputStream source, String contentType)
            throws IOException, SAXException, TikaException {
        return extract(source, contentType).pages();
    }

    /**
     * Selects the page elements owned by the root document. Tika wraps each PDF page in
     * {@code <div class="page">}, but it also inlines embedded parts (mail attachments, archived files,
     * generic embedded objects), which carry their own page divs. Each node is extracted as its own
     * artifact, so the root must ignore its children's pages: this is the DOM analogue of
     * PageIndicesContentHandler's embeddedLevel==0 guard. Formats without pagination emit no page div, so
     * the whole body is returned as a single page.
     */
    private static List<Element> selectRootPages(org.jsoup.nodes.Document document) {
        List<Element> rootPages = new ArrayList<>();
        for (Element pageDiv : document.select("div.page")) {
            if (!isEmbedded(pageDiv)) {
                rootPages.add(pageDiv);
            }
        }
        if (rootPages.isEmpty()) {
            rootPages.add(document.body() != null ? document.body() : document);
        }
        return rootPages;
    }

    // A page div belongs to an embedded child when it is nested inside one of Tika's embedded-part
    // containers, rather than directly under the root document's body.
    private static boolean isEmbedded(Element element) {
        return !element.parents().select(EMBEDDED_CONTAINERS).isEmpty();
    }

    // Renders one page to Markdown: sanitize the live DOM, convert, then trim the surrounding blank lines.
    private String pageToMarkdown(Element page) {
        return MARKDOWN_CONVERTER.convert(sanitize(page)).strip();
    }

    String toXhtml(InputStream source, String contentType) throws IOException, SAXException, TikaException {
        ToXMLContentHandler xhtmlHandler = new ToXMLContentHandler();
        new AutoDetectParser().parse(source, xhtmlHandler, buildMetadata(contentType), buildParseContext());
        return xhtmlHandler.toString();
    }

    // Tika auto-detects the type from the bytes; we only pass a hint when it is specific. A generic
    // application/octet-stream (common for embedded nodes) would mislead detection, so it is dropped.
    private static Metadata buildMetadata(String contentType) {
        Metadata metadata = new Metadata();
        if (isSpecificContentType(contentType)) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        return metadata;
    }

    private static boolean isSpecificContentType(String contentType) {
        return contentType != null && !contentType.isBlank() && !GENERIC_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }

    // Parse context for structure extraction: no OCR (deterministic and fast), and IdentityHtmlMapper so
    // inline formatting (bold/underline) survives Tika instead of being dropped by the DefaultHtmlMapper.
    // Embedded documents are filtered later, at the DOM level (see selectRootPages).
    private static ParseContext buildParseContext() {
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdfConfig);
        context.set(HtmlMapper.class, new IdentityHtmlMapper());
        return context;
    }

    /**
     * Sanitizes one page (live jsoup DOM) to a safe Markdown-ready HTML subset (see SAFELIST). Empty
     * paragraphs are removed first so they do not survive conversion as stray "<br />" noise.
     */
    String sanitize(Element page) {
        removeEmptyParagraphs(page);
        return Jsoup.clean(page.html(), SAFELIST);
    }

    // Tika emits empty paragraphs (<p/> and <p><br/></p>) between blocks. Drop paragraphs that carry no
    // text, but keep those that hold an image (their text is blank yet they are not empty).
    private static void removeEmptyParagraphs(Element page) {
        for (Element paragraph : page.select("p")) {
            if (isBlankParagraph(paragraph)) {
                paragraph.remove();
            }
        }
    }

    private static boolean isBlankParagraph(Element paragraph) {
        return paragraph.text().isBlank() && paragraph.select("img").isEmpty();
    }
}
