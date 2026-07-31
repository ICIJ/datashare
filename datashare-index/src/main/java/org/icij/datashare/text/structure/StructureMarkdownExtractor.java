package org.icij.datashare.text.structure;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.DocumentSelector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.parser.html.IdentityHtmlMapper;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
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
import java.util.Locale;
import java.util.Set;

/**
 * Converts a document's source bytes into a per-page rendering of its structure. Pipeline: Tika XHTML
 * -> split on <div class="page"> -> per-page sanitize -> flexmark html2md. OCR is disabled and
 * embedded documents contribute no pages, so the same bytes always yield byte-identical output, which
 * the content-addressed cache relies on.
 */
public class StructureMarkdownExtractor {

    private static final String GENERIC_CONTENT_TYPE = "application/octet-stream";

    // Containers Tika wraps an embedded part's output in: a mail part, an archived file, or a generic
    // embedded object (PDF-in-PDF, OLE objects in Office files, ...).
    private static final String EMBEDDED_CONTAINERS = "div.embedded, div.email-entry, div.package-entry";

    private static final String PAGE_DIVS = "div.page";

    private static final String XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml";

    // Relaxed safelist minus <u>: keeps formatting/headings/links/lists/tables; jsoup's Cleaner strips
    // <script>/<style>, on* handlers, and unsafe URL schemes (javascript:/data:). preserveRelativeLinks
    // keeps scheme-less relative URLs (absolute javascript:/data: are still rejected by the protocol
    // allowlist). <u>/<ins> are unwrapped to plain text (avoiding flexmark's non-standard "++text++").
    private static final Safelist SAFELIST = Safelist.relaxed().removeTags("u").preserveRelativeLinks(true);

    // Stateless once built; build once rather than per page.
    private static final FlexmarkHtmlConverter MARKDOWN_CONVERTER = FlexmarkHtmlConverter.builder(
            new MutableDataSet()
                    .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                    .set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 1)).build();

    private static final Set<String> INLINE_BODY_TYPES = Set.of("text/plain", "text/html");

    public record Page(String xhtml, String markdown) {}

    /**
     * Parses {@code source} once and returns one {@link Page} per page of the root document. The
     * caller owns {@code source}: this method reads but does not close it.
     */
    public List<Page> extract(InputStream source, String contentType)
            throws IOException, SAXException, TikaException {
        org.jsoup.nodes.Document document = Jsoup.parse(toXhtml(source, contentType));
        List<Page> pages = new ArrayList<>();
        for (Element page : selectRootPages(document)) {
            // Both formats come from the same DOM, so they can never disagree about a page's content.
            org.jsoup.nodes.Document sanitized = asXhtmlDocument(sanitize(page));
            String markdown = MARKDOWN_CONVERTER.convert(sanitized).strip();
            pages.add(new Page(serializeAsXhtml(sanitized), markdown));
        }
        return pages;
    }

    /**
     * Selects the page elements owned by the root document. Tika wraps each PDF page in
     * {@code <div class="page">}, but a page div can also come from an embedded part, which is
     * extracted as its own artifact, so the root must ignore its children's pages. Formats without
     * pagination emit no page div, so the whole body is a single page.
     */
    private static List<Element> selectRootPages(org.jsoup.nodes.Document document) {
        Element body = document.body() != null ? document.body() : document;
        List<Element> rootPages = new ArrayList<>();
        for (Element pageDiv : document.select(PAGE_DIVS)) {
            if (isRootPage(pageDiv)) {
                rootPages.add(pageDiv);
            }
        }
        if (rootPages.isEmpty()) {
            return List.of(body);
        }
        appendStraysToLastPage(rootPages, body);
        return rootPages;
    }

    // Outside every embedded container, and outside every other page div: a nested page div is part of
    // the rendering of the page holding it, so taking it as a page too would emit its content twice.
    private static boolean isRootPage(Element pageDiv) {
        Element parent = pageDiv.parent();
        return pageDiv.closest(EMBEDDED_CONTAINERS) == null
                && (parent == null || parent.closest(PAGE_DIVS) == null);
    }

    // Content can sit outside the page divs (Tika appends the bookmark outline and AcroForm fields after
    // the last page; source HTML using class="page" can leave text anywhere), and losing document text
    // is worse than a page boundary one node off, so detaching the pages leaves it behind and it all
    // goes to the last page. Embedded containers are not stripped: what reaches this DOM inside one is
    // the root's own text either way (see isOwnBody), so a container decides page boundaries and never
    // whether text survives.
    private static void appendStraysToLastPage(List<Element> rootPages, Element body) {
        rootPages.forEach(Element::remove);
        // Their content is the artifact of the node extracted for that part, not the root's.
        body.select(EMBEDDED_CONTAINERS).forEach(Element::remove);
        Element lastPage = rootPages.get(rootPages.size() - 1);
        // Copy first: appending reparents a node, which would derail an iteration over the live list.
        new ArrayList<>(body.childNodes()).forEach(lastPage::appendChild);
    }

    // Wraps a sanitized fragment so a stored page-NNNN.xhtml parses as application/xhtml+xml rather than
    // a bare body fragment. The re-parse is also what turns the cleaned HTML into XML (void elements
    // self-closed). jsoup inserts an empty <head>: harmless, still valid XHTML.
    private static org.jsoup.nodes.Document asXhtmlDocument(String sanitizedFragment) {
        return Jsoup.parse(
                "<html xmlns=\"" + XHTML_NAMESPACE + "\"><body>" + sanitizedFragment + "</body></html>");
    }

    // Applied after the Markdown conversion, so no output setting can reach the converter.
    private static String serializeAsXhtml(org.jsoup.nodes.Document page) {
        page.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .prettyPrint(false);
        return page.html();
    }

    String toXhtml(InputStream source, String contentType) throws IOException, SAXException, TikaException {
        ToXMLContentHandler xhtmlHandler = new ToXMLContentHandler();
        new AutoDetectParser().parse(source, xhtmlHandler, buildMetadata(contentType), buildParseContext());
        return xhtmlHandler.toString();
    }

    // A generic application/octet-stream hint (common for embedded nodes) would mislead Tika's own
    // detection, so only a specific type is passed on.
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

    // No OCR, no recursion into embedded parts, and IdentityHtmlMapper so inline formatting survives
    // instead of being dropped by the DefaultHtmlMapper. Disabling OCR takes both configs: PDFParserConfig
    // only governs a scanned PDF page, while a standalone image goes through TesseractOCRParser, which it
    // does not reach. Output would otherwise depend on the tesseract build and langpacks, which taskInput
    // does not record.
    static ParseContext buildParseContext() {
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        context.set(PDFParserConfig.class, pdfConfig);
        TesseractOCRConfig tesseractConfig = new TesseractOCRConfig();
        tesseractConfig.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, tesseractConfig);
        context.set(HtmlMapper.class, new IdentityHtmlMapper());
        // Tika otherwise appends every embedded part's XHTML into this document's handler, buffering a
        // mail archive's whole recursive tree in heap only for the page selection to throw it away. A
        // DocumentSelector rather than our own EmbeddedDocumentExtractor: it is the hook
        // ParsingEmbeddedDocumentExtractor consults, so the delegate-parser wiring Tika does stays.
        context.set(DocumentSelector.class, StructureMarkdownExtractor::isOwnBody);
        return context;
    }

    // Tika routes a mail's own text or html body through the embedded extractor too, with no
    // resourceName since it is not a part anyone detaches, and extract-lib gives it no document of its
    // own: refusing it would leave every email's structure artifact empty. The disposition cannot
    // narrow this down, since MailContentHandler defaults it to ATTACHMENT for a part with no
    // Content-Disposition header. Accepted consequence: a nameless attachment text part is inlined,
    // duplicating text that has its own artifact, while every named part is still refused.
    static boolean isOwnBody(Metadata metadata) {
        return metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) == null
                && INLINE_BODY_TYPES.contains(baseContentType(metadata));
    }

    private static String baseContentType(Metadata metadata) {
        String contentType = metadata.get(Metadata.CONTENT_TYPE);
        return contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Sanitizes one page (live jsoup DOM) to a safe Markdown-ready HTML subset (see SAFELIST). Empty
     * paragraphs are removed first so they do not survive conversion as stray "<br />" noise.
     */
    String sanitize(Element page) {
        removeEmptyParagraphs(page);
        return Jsoup.clean(page.html(), SAFELIST);
    }

    // Tika emits empty paragraphs (<p/>, <p><br/></p>) between blocks. A paragraph holding only an image
    // has blank text but is not empty, so it stays.
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
