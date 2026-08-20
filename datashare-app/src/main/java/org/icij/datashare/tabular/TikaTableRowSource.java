package org.icij.datashare.tabular;

import org.apache.tika.exception.TikaException;
import org.icij.datashare.text.structure.StructureMarkdownExtractor;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.OcrSettings;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The tier-2 fallback: rows out of the tables Tika renders, covering every format whose parser emits
 * table markup. Reuses StructureMarkdownExtractor rather than setting up its own parse, because that
 * class already owns the resilient PST parser swap, the output cap, the page splitting and the
 * sanitizer. Its Markdown rendering is ignored here; if that shows up in a profile, the fix is an
 * xhtml-only method on that class rather than a parser in this one.
 *
 * Two things differ from tier 1, by nature rather than by omission. Cell values arrive already
 * formatted with the cell type discarded, so a date is whatever the source rendered it as and the
 * mapping's own date format has to handle it. And the extractor's output cap means an oversized
 * document throws rather than importing a truncated table, which is the behaviour a data import
 * needs; large files belong on tier 1.
 *
 * Cell text is stripped here while tier 1 leaves values untouched: Tika's XHTML rendering introduces
 * its own indentation and newlines inside a cell, so the whitespace being removed is the renderer's
 * rather than the document's.
 */
public class TikaTableRowSource implements RowSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(TikaTableRowSource.class);

    /** Rows of this table only: a table nested inside a cell keeps its own rows, and thead, tbody and
     *  tfoot survive the sanitizer, so a row is either a direct child or one level down. */
    private static final String ROW_SELECTOR = ">tr, >thead>tr, >tbody>tr, >tfoot>tr";

    /** Only the types whose Tika parser was confirmed to emit table markup. Deliberately not a
     *  catch-all: claiming every unclaimed type would make this reader own application/pdf and force
     *  a future PDF table extractor to displace an incumbent. */
    public static final Set<String> SUPPORTED = Set.of(
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.ms-excel.sheet.binary.macroenabled.12",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/html",
            "application/vnd.apple.numbers");

    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        Element table = selectTable(tables(source, options), options.table());
        Elements tableRows = table.select(ROW_SELECTOR);
        if (tableRows.isEmpty()) {
            throw new IllegalArgumentException("no header row: the table is empty");
        }
        List<String> headers = Row.headers(cells(tableRows.get(0)));

        List<Row> rows = new ArrayList<>();
        long surplus = 0;
        for (int index = 1; index < tableRows.size(); index++) {
            List<String> values = cells(tableRows.get(index));
            if (values.stream().allMatch(String::isEmpty)) {
                continue;
            }
            if (values.size() > headers.size()) {
                surplus++;
            }
            // Tolerated rather than refused, unlike tier 1: a stray trailing cell is common in
            // real-world markup, and the rest of the row still lines up with the header.
            rows.add(new Row(rows.size() + 1L, Row.values(headers, values)));
        }
        if (surplus > 0) {
            LOGGER.info("dropped the cells past the {} declared columns in {} rows", headers.size(), surplus);
        }
        return rows.stream().onClose(() -> close(source));
    }

    // OCR off: a tabular source does not need it, and leaving it off keeps the parse cheap and its
    // output deterministic.
    private List<Element> tables(InputStream source, RowSourceOptions options) throws IOException {
        List<Page> pages;
        try {
            pages = extractor.extract(source, options.contentType(), null, OcrSettings.NONE);
        } catch (SAXException | TikaException parseFailure) {
            throw new IOException("parsing the tabular source failed", parseFailure);
        }
        List<Element> tables = new ArrayList<>();
        for (Page page : pages) {
            tables.addAll(Jsoup.parse(page.xhtml()).select("table"));
        }
        return tables;
    }

    private static Element selectTable(List<Element> tables, Integer requested) {
        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "the source holds no table, so it has no rows to read");
        }
        int index = requested == null ? 1 : requested;
        if (index < 1 || index > tables.size()) {
            throw new IllegalArgumentException(
                    "no table at index " + index + ": the source holds " + tables.size());
        }
        return tables.get(index - 1);
    }

    /**
     * A merged cell makes column alignment undefined, and silently misaligning every value under a
     * header is worse than refusing the file, so a span anywhere in a row is rejected. Both th and td
     * count as cells: a header row uses either depending on who wrote the document.
     */
    private static List<String> cells(Element tableRow) {
        List<String> values = new ArrayList<>();
        for (Element cell : tableRow.select(">th, >td")) {
            if (cell.hasAttr("colspan") || cell.hasAttr("rowspan")) {
                throw new IllegalArgumentException(
                        "merged cells make the columns ambiguous: remove the colspan or rowspan");
            }
            values.add(cell.text().strip());
        }
        return values;
    }

    private static void close(InputStream source) {
        try {
            source.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the tabular source failed", e);
        }
    }
}
