package org.icij.datashare.tabular;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DelimitedRowSource implements RowSource {
    /** text/tsv is the type Tika 3.3.0 writes when its sniffer finds a tab delimiter in a file whose
     *  name does not end in .tsv; text/tab-separated-values is the one it keeps for the extension. */
    public static final Set<String> SUPPORTED =
            Set.of("text/csv", "text/tab-separated-values", "text/tsv", "text/plain");

    private static final Set<String> TAB_SEPARATED_TYPES =
            Set.of("text/tab-separated-values", "text/tsv");

    private static final char DEFAULT_DELIMITER = ',';

    // Thousands of rows, so the first accented character of a realistic export falls inside it. A file
    // that is pure ascii this far and latin-1 afterwards decodes as utf-8 and shows replacement
    // characters, which is a visible failure rather than the silent mojibake this probe prevents.
    private static final int CHARSET_PROBE_BYTES = 64 * 1024;

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        CSVParser parser = open(source, options);
        try {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) {
                throw new IllegalArgumentException("no header row: the source is empty");
            }
            List<String> headers = Row.headers(records.next().toList());
            return stream(records, headers).onClose(() -> close(parser));
        } catch (RuntimeException failure) {
            closeQuietly(parser);
            throw failure;
        }
    }

    // Releases the source itself when the parser cannot be built, since no stream reaches the caller
    // to be closed on that path.
    private static CSVParser open(InputStream source, RowSourceOptions options) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(source);
        try {
            // CSVParser.parse over CSVParser.builder(): the builder's setReader is inherited from
            // commons-io and returns the supertype, so the fluent chain does not typecheck.
            return CSVParser.parse(buffered, charset(buffered, options.charset()), format(options));
        } catch (IOException | RuntimeException failure) {
            closeQuietly(buffered);
            throw failure;
        }
    }

    /**
     * A UTF-8 file read as a single-byte charset never reports a decoding error, so Tika's charset
     * detector settling on ISO-8859-1 for a large mostly-ASCII export would mojibake every accented
     * value in silence. The reverse does report, so probing the head for valid UTF-8 is what tells the
     * two apart; the detected charset is kept only when that probe fails.
     */
    private static Charset charset(BufferedInputStream source, Charset detected) throws IOException {
        if (detected == null || StandardCharsets.UTF_8.equals(detected)) {
            return StandardCharsets.UTF_8;
        }
        source.mark(CHARSET_PROBE_BYTES);
        byte[] head = source.readNBytes(CHARSET_PROBE_BYTES);
        source.reset();
        return decodesAsUtf8(head) ? StandardCharsets.UTF_8 : detected;
    }

    private static boolean decodesAsUtf8(byte[] head) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // endOfInput false, so a multi-byte sequence cut in half by the probe boundary underflows
        // instead of counting as malformed.
        CoderResult result = decoder.decode(
                ByteBuffer.wrap(head), CharBuffer.allocate(head.length + 1), false);
        return !result.isError();
    }

    private static CSVFormat format(RowSourceOptions options) {
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder().setDelimiter(delimiter(options));
        if (options.quote() != null) {
            builder.setQuote(options.quote());
        }
        return builder.get();
    }

    // Tika records csv:delimiter only for the files its sniffer typed, and it never types a .tsv that
    // way, so without this the one format whose delimiter its content type already states is the one
    // parsed with a comma.
    private static char delimiter(RowSourceOptions options) {
        if (options.delimiter() != null) {
            return options.delimiter();
        }
        return options.contentType() != null && TAB_SEPARATED_TYPES.contains(options.contentType())
                ? '\t' : DEFAULT_DELIMITER;
    }

    private static Stream<Row> stream(Iterator<CSVRecord> records, List<String> headers) {
        Iterator<Row> rows = new Iterator<>() {
            private long number = 0;
            private List<String> pending;

            @Override
            public boolean hasNext() {
                // A record whose every field is empty is skipped, as it is in every other reader, and
                // consumes no row number; commons-csv already drops a truly blank line.
                while (pending == null && hasNextRecord()) {
                    List<String> cells = records.next().toList();
                    if (!cells.stream().allMatch(String::isEmpty)) {
                        pending = cells;
                    }
                }
                return pending != null;
            }

            // commons-csv's iterator prefetches the next record, so a malformed record (an
            // unterminated quote, for instance) surfaces as an UncheckedIOException or an
            // IllegalStateException from hasNext() rather than from next(); naming the row number is
            // what makes it actionable to whoever wrote the file.
            private boolean hasNextRecord() {
                try {
                    return records.hasNext();
                } catch (UncheckedIOException | IllegalStateException e) {
                    throw new IllegalArgumentException("malformed row " + (number + 1) + ": " + e.getMessage(), e);
                }
            }

            @Override
            public Row next() {
                List<String> cells = pending;
                pending = null;
                number++;
                return new Row(number, Row.values(headers, cells, number));
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(rows, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    private static void close(CSVParser parser) {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the delimited source failed", e);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // the read already failed; the close failure adds nothing the caller can act on
        }
    }
}
