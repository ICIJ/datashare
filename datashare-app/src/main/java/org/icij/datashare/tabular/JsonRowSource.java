package org.icij.datashare.tabular;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.apache.commons.io.IOUtils.closeQuietly;

/**
 * Reads flat records out of JSON. One reader covers both shapes a dump comes in: peeking the first
 * token tells an array of objects apart from line-delimited or concatenated objects, and Jackson
 * iterates both identically once positioned.
 *
 * A record carries its own column names, so the header rules the other readers share do not reach
 * here: a blank key becomes a column named "", keys are not stripped, a duplicate key is
 * last-write-wins, and {@code {"addr.city":"X","addr":{"city":"Y"}}} collides silently.
 */
public class JsonRowSource implements RowSource {
    /** Tika 3.3.0 has no mimetype for jsonl or ndjson, so this is the de facto type, used only as
     *  the internal token connecting an extension to this reader. It never appears on a document. */
    public static final String NDJSON_CONTENT_TYPE = "application/x-ndjson";

    public static final Set<String> SUPPORTED = Set.of("application/json", NDJSON_CONTENT_TYPE);

    // A private mapper rather than the shared one: JsonObjectMapper's StreamReadConstraints are cut
    // for HTTP request bodies, and on a data dump they are wrong in both directions. They raise the
    // single-string cap from 20 MB to 1 GB, which is the guard that matters for a user-supplied file,
    // and they lower the nesting cap from 1000 to 20, which flatten() is built to handle.
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        JsonParser parser = mapper.createParser(source);
        MappingIterator<JsonNode> records;
        try {
            // Positioning inside a root array is what makes readValues yield its elements rather than
            // the array as a single value; for NDJSON the parser is already where it needs to be.
            // An empty source is a failed export, not an empty one: every sibling reader refuses it,
            // and importing zero rows out of a truncated file would report the loss as a success. An
            // explicitly empty array is different, and stays a successful read of nothing, but it is
            // still refused when something follows it: readValues cannot be handed a parser sitting
            // on the array's closing bracket, so this path runs that check itself.
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("no records: the source is empty");
            }
            if (first == JsonToken.START_ARRAY && parser.nextToken() == JsonToken.END_ARRAY) {
                refuseTrailingContent(parser);
                close(parser);
                return Stream.empty();
            }
            records = mapper.readerFor(JsonNode.class).readValues(parser);
        } catch (IOException | RuntimeException failure) {
            // The parser does not own a stream it was handed, so releasing it is not enough.
            closeQuietly(parser);
            closeQuietly(source);
            throw failure;
        }

        Iterator<Row> rows = new Iterator<>() {
            private long number = 0;

            @Override
            public boolean hasNext() {
                // MappingIterator stops at the root array's closing bracket, so anything after it
                // would be dropped without a word: two dumps concatenated by cat would import the
                // first and report success.
                boolean more = hasNextRecord();
                if (!more) {
                    refuseTrailingContent(parser);
                }
                return more;
            }

            private boolean hasNextRecord() {
                try {
                    return records.hasNext();
                } catch (RuntimeException malformed) {
                    throw new IllegalArgumentException(
                            "malformed record " + (number + 1) + ": " + malformed.getMessage(), malformed);
                }
            }

            @Override
            public Row next() {
                JsonNode record = records.next();
                number++;
                // A scalar or an array has no keys to map onto columns, so it would yield a row with
                // no values at all: refusing beats reporting an empty import as a success.
                if (!record.isObject()) {
                    throw new IllegalArgumentException(
                            "row " + number + " is not a json object but a " + record.getNodeType());
                }
                Map<String, String> values = new LinkedHashMap<>();
                try {
                    flatten("", record, values);
                } catch (IllegalArgumentException notTabular) {
                    throw new IllegalArgumentException("row " + number + ": " + notTabular.getMessage(), notTabular);
                }
                return new Row(number, Collections.unmodifiableMap(values));
            }
        };
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(rows,
                                Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() -> close(parser));
    }

    // An array is refused rather than joined or skipped: a joined array is a delimited file inside a
    // cell, whose separator can collide with the data, and a skipped one loses values in silence. If
    // multi-valued properties turn out to matter, the fix is a multi-value seam in Row.
    private static void flatten(String prefix, JsonNode node, Map<String, String> values) {
        node.properties().forEach(field -> {
            String column = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flatten(column, value, values);
            } else if (value.isArray()) {
                throw new IllegalArgumentException(
                        "column " + column + " holds an array, which no table cell can carry");
            } else {
                values.put(column, value.isNull() ? "" : value.asText());
            }
        });
    }

    private static void refuseTrailingContent(JsonParser parser) {
        try {
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(
                        "content after the end of the json array, at " + parser.currentLocation());
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("reading past the json array failed", unreadable);
        }
    }

    private static void close(JsonParser parser) {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the json source failed", e);
        }
    }
}
