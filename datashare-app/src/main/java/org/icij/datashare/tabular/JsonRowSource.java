package org.icij.datashare.tabular;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads flat records out of JSON. One reader covers both shapes a dump comes in: peeking the first
 * token tells an array of objects apart from line-delimited or concatenated objects, and Jackson
 * iterates both identically once positioned.
 */
public class JsonRowSource implements RowSource {
    /** Tika 3.3.0 has no mimetype for jsonl or ndjson, so this is the de facto type, used only as
     *  the internal token connecting an extension to this reader. It never appears on a document. */
    public static final String NDJSON_CONTENT_TYPE = "application/x-ndjson";

    public static final Set<String> SUPPORTED = Set.of("application/json", NDJSON_CONTENT_TYPE);

    private static final String SCALAR_ARRAY_SEPARATOR = "|";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean supports(String contentType) {
        return SUPPORTED.contains(contentType);
    }

    @Override
    public Stream<Row> rows(InputStream source, RowSourceOptions options) throws IOException {
        JsonParser parser = mapper.createParser(source);
        // Positioning inside a root array is what makes readValues yield its elements rather than the
        // array as a single value; for NDJSON the parser is already where it needs to be.
        if (parser.nextToken() == JsonToken.START_ARRAY && parser.nextToken() == JsonToken.END_ARRAY) {
            close(parser);
            return Stream.empty();
        }
        MappingIterator<JsonNode> records = mapper.readerFor(JsonNode.class).readValues(parser);

        Iterator<Row> rows = new Iterator<>() {
            private long number = 0;

            @Override
            public boolean hasNext() {
                return records.hasNext();
            }

            @Override
            public Row next() {
                Map<String, String> values = new HashMap<>();
                flatten("", records.next(), values);
                return new Row(++number, values);
            }
        };
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(rows,
                                Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() -> close(parser));
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> values) {
        node.properties().forEach(field -> {
            String column = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flatten(column, value, values);
            } else if (value.isArray()) {
                joinScalars(column, value, values);
            } else {
                values.put(column, value.isNull() ? "" : value.asText());
            }
        });
    }

    /**
     * An array of scalars joins on a fixed separator so a mapping can rely on splitting it. An array
     * of objects is skipped: there is no column name that would describe it. If multi-valued
     * properties turn out to matter, the fix is a multi-value seam in Row, not a cleverer separator.
     */
    private static void joinScalars(String column, JsonNode array, Map<String, String> values) {
        StringBuilder joined = new StringBuilder();
        for (JsonNode element : array) {
            if (element.isContainerNode()) {
                return;
            }
            if (!joined.isEmpty()) {
                joined.append(SCALAR_ARRAY_SEPARATOR);
            }
            joined.append(element.isNull() ? "" : element.asText());
        }
        values.put(column, joined.toString());
    }

    private static void close(JsonParser parser) {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException("closing the json source failed", e);
        }
    }
}
