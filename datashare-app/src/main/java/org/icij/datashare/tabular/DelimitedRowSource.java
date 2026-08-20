package org.icij.datashare.tabular;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DelimitedRowSource implements RowSource {
    private static final Set<String> SUPPORTED =
            Set.of("text/csv", "text/tab-separated-values", "text/plain");

    private static final char DEFAULT_DELIMITER = ',';

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        Charset charset = options.charset() == null ? StandardCharsets.UTF_8 : options.charset();
        // CSVParser.parse over CSVParser.builder(): the builder's setReader is inherited from
        // commons-io and returns the supertype, so the fluent chain does not typecheck.
        CSVParser parser = CSVParser.parse(source, charset, format(options));

        Iterator<CSVRecord> records = parser.iterator();
        if (!records.hasNext()) {
            parser.close();
            throw new IllegalArgumentException("no header row: the source is empty");
        }
        List<String> headers = Row.headers(records.next().toList());

        return stream(records, headers).onClose(() -> close(parser));
    }

    private static CSVFormat format(RowSourceOptions options) {
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder()
                .setDelimiter(options.delimiter() == null ? DEFAULT_DELIMITER : options.delimiter());
        if (options.quote() != null) {
            builder.setQuote(options.quote());
        }
        return builder.get();
    }

    private static Stream<Row> stream(Iterator<CSVRecord> records, List<String> headers) {
        Iterator<Row> rows = new Iterator<>() {
            private long number = 0;

            @Override
            public boolean hasNext() {
                // commons-csv's iterator prefetches the next record here, so a malformed record (an
                // unterminated quote, for instance) surfaces as an UncheckedIOException or an
                // IllegalStateException from this call rather than from next(); naming the row number
                // is what makes it actionable to whoever wrote the file.
                try {
                    return records.hasNext();
                } catch (UncheckedIOException | IllegalStateException e) {
                    throw new IllegalArgumentException("malformed row " + (number + 1) + ": " + e.getMessage(), e);
                }
            }

            @Override
            public Row next() {
                return new Row(++number, values(records.next(), headers));
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(rows, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    private static Map<String, String> values(CSVRecord record, List<String> headers) {
        Map<String, String> values = new HashMap<>();
        for (int column = 0; column < headers.size() && column < record.size(); column++) {
            if (headers.get(column) != null) {
                values.put(headers.get(column), record.get(column));
            }
        }
        return values;
    }

    private static void close(CSVParser parser) {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the delimited source failed", e);
        }
    }
}
