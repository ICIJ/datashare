package org.icij.datashare.text.structure;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.TextNode;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Converts a document's source bytes into a deterministic Markdown rendering of its structure.
 * Pipeline: Tika XHTML (un-flatten) -> jsoup page marking -> flexmark html2md -> numbered page comments.
 * OCR is disabled so the same bytes always yield byte-identical Markdown (required for the
 * content-addressed, skip-if-present cache).
 */
public class StructureMarkdownExtractor {
    static final String PAGE_SENTINEL = "DATASHAREPAGEBREAKSENTINEL";

    /**
     * Converts {@code source} into structure Markdown. The caller owns {@code source}: this method
     * reads but does not close it.
     */
    public String extract(InputStream source, String contentType) throws IOException, SAXException, TikaException {
        String xhtml = toXhtml(source, contentType);
        String marked = markPages(xhtml);
        String markdown = toMarkdown(marked);
        return numberPages(markdown);
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
        new AutoDetectParser().parse(source, handler, metadata, context);
        return handler.toString();
    }

    String markPages(String xhtml) {
        org.jsoup.nodes.Document doc = Jsoup.parse(xhtml);
        // Tika's PDF parser wraps each page in <div class="page">; that div is emitted as raw SAX and so
        // survives ToXMLContentHandler, giving one hit per source page. We turn each one into a leading
        // sentinel. The sentinel lives in its own <p> rather than a bare TextNode because flexmark's
        // html2md drops/normalises loose text between block-level elements, whereas a <p> survives as a
        // standalone literal line that numberPages can later renumber.
        for (org.jsoup.nodes.Element page : doc.select("div.page")) {
            org.jsoup.nodes.Element marker = doc.createElement("p");
            marker.appendChild(new TextNode(PAGE_SENTINEL));
            page.before(marker);
        }
        return doc.body() != null ? doc.body().html() : doc.html();
    }

    String toMarkdown(String html) {
        // SETEXT_HEADINGS defaults to true, which renders <h1> as an underlined Setext heading
        // ("Title\n====="). Force ATX style ("# Title") so headings always carry an explicit level
        // marker. All other options stay at their pinned 0.64.8 defaults to keep output deterministic.
        MutableDataSet options = new MutableDataSet();
        options.set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false);
        return FlexmarkHtmlConverter.builder(options).build().convert(html);
    }

    String numberPages(String markdown) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        int page = 0;
        int idx;
        while ((idx = markdown.indexOf(PAGE_SENTINEL, from)) >= 0) {
            page++;
            out.append(markdown, from, idx).append("<!-- page ").append(page).append(" -->");
            from = idx + PAGE_SENTINEL.length();
        }
        out.append(markdown.substring(from));
        return out.toString();
    }
}
