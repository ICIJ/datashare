package org.icij.datashare.text.structure;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.DocumentSelector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.parser.html.IdentityHtmlMapper;
import org.apache.tika.parser.microsoft.pst.OutlookPSTParser;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.parser.ResilientOutlookPSTParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts a document's source bytes into a per-page rendering of its structure. Pipeline: Tika XHTML
 * -> split on {@code <div class="page">} -> per-page sanitize -> flexmark html2md. Embedded documents
 * contribute no pages, so the same bytes under the same {@link OcrSettings} always yield byte-identical
 * output, which the content-addressed cache relies on: OCR is the one input that is not the bytes, so
 * the caller passes it and records it in the artifact's fingerprint.
 */
public class StructureMarkdownExtractor {

    /** The OCR the INDEX stage applied to this document, so a page holds the text the content field
     *  holds. Two knobs because two parsers are, as extract-lib splits them: {@code --ocr} for a
     *  standalone image, {@code --ocrStrategy} for a scanned PDF (whose default really is NO_OCR). */
    public record OcrSettings(boolean images, PDFParserConfig.OCR_STRATEGY pdfStrategy) {
        public static final OcrSettings NONE = new OcrSettings(false, PDFParserConfig.OCR_STRATEGY.NO_OCR);
    }

    private static final String GENERIC_CONTENT_TYPE = "application/octet-stream";

    // Containers Tika wraps an embedded part's output in: a mail part, an archived file, or a generic
    // embedded object (PDF-in-PDF, OLE objects in Office files, ...).
    private static final String EMBEDDED_CONTAINERS = "div.embedded, div.email-entry, div.package-entry";

    private static final String PAGE_DIVS = "div.page";

    private static final String XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml";

    // Relaxed minus <u>, which is unwrapped to avoid flexmark's non-standard "++text++". An <img> keeps
    // everything but its src: a stored page is rendered, so a remote image is a beacon reporting the
    // reader's IP and which document they opened, and no protocol allowlist can tell one from a benign
    // image ("//host/pixel.png" passes). A link target stays, since rendering a page does not follow it.
    // A Markdown source's "~~struck~~" and "---" parse to <del> and <hr>, which relaxed() has neither
    // of: both are formatting-only, carry no attributes and no active content, so both are added.
    // "class" on a <code> only, so a fenced block keeps the language the converter needs to write the
    // fence back. jsoup cannot filter attribute values, and html2md writes this one straight into the
    // markdown fence info string with no re-sanitization after (see keepOnlyLanguageClass), so what
    // reaches Jsoup.clean is already reduced to a single recognized language token or nothing.
    private static final Safelist SAFELIST = Safelist.relaxed().removeTags("u").addTags("del", "hr")
            .addAttributes("code", "class")
            .removeAttributes("img", "src").preserveRelativeLinks(true);

    // Pretty-printing would bake jsoup's indentation into the stored XHTML, so the page bytes would
    // depend on formatting defaults nothing tracks. Cloned per use so no page can mutate it.
    private static final Document.OutputSettings COMPACT_OUTPUT = new Document.OutputSettings().prettyPrint(false);

    // jsoup's protocol allowlist runs on the resolved URL, so a relative href with no base to resolve
    // against is dropped as an unknown scheme. jsoup's 3-argument clean() substitutes its own dummy
    // host for that reason; the overload taking output settings does not. Never reaches the stored
    // bytes, since preserveRelativeLinks keeps the attribute's original value.
    private static final String RELATIVE_LINK_BASE = "https://dummy.example/";

    // ToXMLContentHandler buffers the whole rendering, and the pipeline then holds it several times over
    // (buffer, string, jsoup DOM, every page's XHTML and Markdown), times --parallelism. Nothing on the
    // produce path catches an OutOfMemoryError, so the parse is stopped instead.
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 16_000_000;

    // The same parser set the INDEX stage uses: extract-lib swaps Tika's stock OutlookPSTParser, which
    // abandons the rest of a PST or OST once one message fails, for the resilient one (Extractor.java:259).
    // Off Tika's stock set, a mail container's structure would stop at its first bad message. Null-checked
    // because replaceParser returns null for a non-composite parser, which would then NPE per document.
    private static final Parser RESILIENT_PARSER = Objects.requireNonNull(
            Extractor.replaceParser(TikaConfig.getDefaultConfig().getParser(), OutlookPSTParser.class,
                    parser -> new ResilientOutlookPSTParser()),
            "Tika's default parser is not a CompositeParser, so the resilient PST parser cannot be swapped in");

    private final int maxOutputChars;

    public StructureMarkdownExtractor() {
        this(DEFAULT_MAX_OUTPUT_CHARS);
    }

    StructureMarkdownExtractor(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    // Stateless once built; build once rather than per page. The list delimiter and thematic break are
    // pinned to what a Markdown source writes them as, since flexmark's defaults ("*", and
    // "*** ** * ** ***") would rewrite a README's own syntax on the way back out.
    private static final FlexmarkHtmlConverter MARKDOWN_CONVERTER = FlexmarkHtmlConverter.builder(
            new MutableDataSet()
                    .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                    .set(FlexmarkHtmlConverter.UNORDERED_LIST_DELIMITER, '-')
                    .set(FlexmarkHtmlConverter.THEMATIC_BREAK, "---")
                    .set(FlexmarkHtmlConverter.MAX_BLANK_LINES, 1)).build();

    // Tika has no Markdown parser: a Markdown document goes through TextAndCSVParser, which hands back
    // the whole file as a single literal text node, so every "**" reaching the converter above is text
    // and comes out escaped as "\*\*". Parsing that text as Markdown first makes it real elements, which
    // the converter then writes back unescaped. Tables and strikethrough are both on because the
    // converter renders them back as GFM syntax (a table, "~~struck~~") and would otherwise see a wall
    // of escaped pipes or literal tildes.
    private static final MutableDataSet MARKDOWN_OPTIONS = new MutableDataSet()
            .set(com.vladsch.flexmark.parser.Parser.EXTENSIONS,
                    List.of(TablesExtension.create(), StrikethroughExtension.create()));

    private static final com.vladsch.flexmark.parser.Parser MARKDOWN_PARSER =
            com.vladsch.flexmark.parser.Parser.builder(MARKDOWN_OPTIONS).build();

    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder(MARKDOWN_OPTIONS).build();

    // What Tika 3.3.0 detects Markdown as. The IANA name (text/markdown, RFC 7763) is deliberately not
    // listed too: Tika never emits it, so it would be an untested branch standing in for a rename that
    // has not happened.
    private static final String MARKDOWN_TYPE = "text/x-web-markdown";

    private static final Set<String> INLINE_BODY_TYPES = Set.of("text/plain", "text/html");

    public record Page(String xhtml, String markdown) {}

    /**
     * Parses {@code source} once and returns one {@link Page} per page of the root document. The
     * caller owns {@code source}: this method reads but does not close it. {@code contentType} and
     * {@code filename} are detection hints, either of which may be null.
     */
    public List<Page> extract(InputStream source, String contentType, String filename, OcrSettings ocr)
            throws IOException, SAXException, TikaException {
        // Kept rather than built inside toXhtml: the parse fills it with the type Tika detected.
        Metadata metadata = buildMetadata(contentType, filename);
        org.jsoup.nodes.Document document = Jsoup.parse(toXhtml(source, metadata, ocr));
        if (isMarkdown(metadata)) {
            document = parseAsMarkdown(document);
        }
        List<Page> pages = new ArrayList<>();
        for (Element page : selectRootPages(document)) {
            // Both formats come from the same DOM, so they can never disagree about a page's content.
            org.jsoup.nodes.Document sanitized = asXhtmlDocument(sanitize(page));
            String markdown = MARKDOWN_CONVERTER.convert(sanitized).strip();
            pages.add(new Page(serializeAsXhtml(sanitized), markdown));
        }
        return pages;
    }

    // The type Tika settled on rather than the caller's hint: AutoDetectParser writes what it detected
    // into the metadata it is given, so an embedded .md arriving with its container's generic type is
    // recognised here too.
    private static boolean isMarkdown(Metadata metadata) {
        return MARKDOWN_TYPE.equals(baseContentType(metadata));
    }

    // Replaces Tika's single-text-node rendering with a DOM parsed from the Markdown that node holds.
    // Nothing downstream knows the difference: page selection, the safelist and the converter run on it
    // exactly as they run on a parser's own XHTML.
    private static org.jsoup.nodes.Document parseAsMarkdown(org.jsoup.nodes.Document tikaOutput) {
        return Jsoup.parse(MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(tikaOutput.body().wholeText())));
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
        Element lastPage = rootPages.get(rootPages.size() - 1);
        // Copy first: appending reparents a node, which would derail an iteration over the live list.
        new ArrayList<>(body.childNodes()).forEach(lastPage::appendChild);
    }

    // Wraps a sanitized fragment so a stored page-N.xhtml parses as application/xhtml+xml rather than
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

    String toXhtml(InputStream source, Metadata metadata, OcrSettings ocr)
            throws IOException, SAXException, TikaException {
        ToXMLContentHandler xhtmlHandler = new ToXMLContentHandler();
        new AutoDetectParser(RESILIENT_PARSER).parse(source,
                new WriteOutContentHandler(xhtmlHandler, maxOutputChars),
                metadata, buildParseContext(ocr));
        return xhtmlHandler.toString();
    }

    // The two hints Tika's detector takes, both as deterministic as the bytes themselves. A generic
    // application/octet-stream type (common for embedded nodes) would mislead detection rather than help
    // it, so only a specific one is passed on; the filename is passed as it is. The caller keeps the
    // instance: AutoDetectParser writes the type it detected into it, which is what decides whether the
    // Markdown branch above applies.
    static Metadata buildMetadata(String contentType, String filename) {
        Metadata metadata = new Metadata();
        if (isSpecificContentType(contentType)) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        if (filename != null && !filename.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        return metadata;
    }

    private static boolean isSpecificContentType(String contentType) {
        return contentType != null && !contentType.isBlank() && !GENERIC_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }

    // The caller's OCR, no recursion into embedded parts, and IdentityHtmlMapper so inline formatting
    // survives instead of being dropped by the DefaultHtmlMapper. OCR takes both configs: PDFParserConfig
    // only governs a scanned PDF page, while a standalone image goes through TesseractOCRParser, which it
    // does not reach. With OCR on, output depends on the tesseract build and langpacks, which taskInput
    // does not record: the same exposure PageArtifact already accepts, and the price of pages that hold
    // what the content field holds.
    static ParseContext buildParseContext(OcrSettings ocr) {
        ParseContext context = new ParseContext();
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(ocr.pdfStrategy());
        context.set(PDFParserConfig.class, pdfConfig);
        TesseractOCRConfig tesseractConfig = new TesseractOCRConfig();
        tesseractConfig.setSkipOcr(!ocr.images());
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
    public static boolean isOwnBody(Metadata metadata) {
        return metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) == null
                && INLINE_BODY_TYPES.contains(baseContentType(metadata));
    }

    private static String baseContentType(Metadata metadata) {
        String contentType = metadata.get(Metadata.CONTENT_TYPE);
        return contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Sanitizes one page (live jsoup DOM) to a safe Markdown-ready HTML subset (see SAFELIST). Two
     * pre-passes run before Jsoup.clean: empty paragraphs are removed so they do not survive conversion
     * as stray "<br />" noise, and a code block's class is reduced to a single language token so nothing
     * jsoup lets through unfiltered reaches the fence info string html2md writes from it.
     */
    String sanitize(Element page) {
        removeEmptyParagraphs(page);
        keepOnlyLanguageClass(page);
        // A detached page has no output settings of its own and would serialize with jsoup's
        // pretty-printing defaults. Moving rather than copying: the nodes are not held twice, at the
        // cost of emptying the argument, which each page is only handed to once.
        Document holder = Document.createShell(RELATIVE_LINK_BASE);
        holder.outputSettings(COMPACT_OUTPUT.clone());
        holder.body().insertChildren(0, new ArrayList<>(page.childNodes()));
        return Jsoup.clean(holder.body().html(), RELATIVE_LINK_BASE, SAFELIST, COMPACT_OUTPUT.clone());
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

    // A code class is an unsanitized channel: jsoup cannot filter an attribute's value, and html2md
    // writes this one verbatim into the markdown fence info string with no re-sanitization afterward.
    // A value holding a newline and a closing fence would otherwise close the fence early and drop the
    // rest of the value in as a raw HTML block at the top level. classNames() splits on whitespace, so
    // a newline inside the value cannot survive into a kept token.
    private static final Pattern LANGUAGE_CLASS = Pattern.compile("language-[\\w.+#-]+");

    private static void keepOnlyLanguageClass(Element page) {
        for (Element code : page.select("code[class]")) {
            String language = code.classNames().stream().filter(c -> LANGUAGE_CLASS.matcher(c).matches())
                    .findFirst().orElse(null);
            if (language == null) code.removeAttr("class"); else code.attr("class", language);
        }
    }

    private static boolean isBlankParagraph(Element paragraph) {
        return paragraph.text().isBlank() && paragraph.select("img").isEmpty();
    }
}
