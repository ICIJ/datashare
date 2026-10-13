package org.icij.datashare.tabular;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.icij.datashare.json.JsonObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import static java.util.stream.Collectors.joining;

/**
 * Reads a mapping written by hand, as a CLI file or a request body. The shared mapper ignores unknown
 * fields, so a misspelled optional one (rootId, dateFormat) would be saved without the setting its
 * author wrote, under an id that can never change: here an unknown field is refused instead. The
 * stored definition keeps the lenient read, so a mapping still loads after a field is dropped. Every
 * refusal names the field, never a Java type.
 */
public class ExtractionMappingReader {
    private static final ObjectReader STRICT_READER = JsonObjectMapper.getMapper().readerFor(ExtractionMapping.class)
                                                                      .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ObjectReader TREE_READER =
            JsonObjectMapper.getMapper().reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ExtractionMappingReader() {}

    /** Parses the document a caller then completes (with the project, the author) in
     *  {@link #read(ObjectNode, String, String)}: a repeated field fails here, where the tree would keep only the last. */
    public static ObjectNode parse(byte[] json) {
        try {
            if (TREE_READER.readTree(json) instanceof ObjectNode node) {
                return node;
            }
        } catch (MismatchedInputException e) {
            throw new MalformedExtractionMapping("the mapping has content after its closing brace", e);
        } catch (JsonProcessingException e) {
            String reason = e.getOriginalMessage();
            if (reason.startsWith("Duplicate field")) {
                throw new MalformedExtractionMapping(reason.replace("Duplicate field", "the mapping repeats the field"),
                                                     e);
            }
            JsonLocation at = e.getLocation();
            throw new MalformedExtractionMapping(at == null ? "the mapping is not valid JSON" :
                                                 "the mapping is not valid JSON, at line %d, column %d".formatted(
                                                         at.getLineNr(), at.getColumnNr()), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new MalformedExtractionMapping("the mapping is not a JSON object");
    }

    /** Stamps the project and the owner its caller imposes. A value the document declares otherwise is
     *  refused, never overwritten: the mapping would land in a project, or with an owner, its author did
     *  not mean, under an id that can never change. A null owner means the mapping has none. */
    public static ExtractionMapping read(ObjectNode node, String projectId, String userId) {
        impose(node, "projectId", projectId, "in project '%s'".formatted(projectId));
        impose(node, "userId", userId, userId == null ? "with no owner" : "for '%s'".formatted(userId));
        return read(node);
    }

    /** Also imposes the id, for a caller that names the mapping itself, like a REST path. */
    public static ExtractionMapping read(ObjectNode node, String id, String projectId, String userId) {
        impose(node, "id", id, "as '%s'".formatted(id));
        return read(node, projectId, userId);
    }

    private static void impose(ObjectNode node, String field, String value, String destination) {
        JsonNode declared = node.path(field);
        if (!declared.isMissingNode() && !declared.isNull() && !declared.asText().equals(value)) {
            throw new MalformedExtractionMapping(
                    "field '%s' is '%s' but the mapping is saved %s: drop the field".formatted(field, declared.asText(),
                                                                                               destination));
        }
        node.put(field, value);
    }

    private static ExtractionMapping read(ObjectNode node) {
        try {
            return STRICT_READER.readValue(node);
        } catch (UnrecognizedPropertyException e) {
            throw new MalformedExtractionMapping("unknown field '%s'".formatted(pathOf(e)), e);
        } catch (ValueInstantiationException e) {
            throw new MalformedExtractionMapping(refusalOf(e), e);
        } catch (MismatchedInputException e) {
            throw new MalformedExtractionMapping("field '%s' has the wrong type".formatted(pathOf(e)), e);
        } catch (JsonProcessingException e) {
            throw new MalformedExtractionMapping(e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The record's constructors refuse a missing field with requireNonNull(field, "field"), so a null
    // pointer with a one-word message names the missing field. Any other null pointer comes from a
    // null inside a field (a list item, a map value), and its message is the JVM's, naming Java code.
    private static String refusalOf(ValueInstantiationException e) {
        String path = pathOf(e);
        Throwable refusal = e.getCause();
        String message = refusal == null ? e.getOriginalMessage() : refusal.getMessage();
        if (refusal instanceof NullPointerException) {
            if (message != null && message.indexOf(' ') < 0) {
                return "missing field '%s'".formatted(path.isEmpty() ? message : path + "." + message);
            }
            return path.isEmpty() ? "the mapping holds a null" : "field '%s' holds a null".formatted(path);
        }
        return path.isEmpty() ? message : path + ": " + message;
    }

    private static String pathOf(JsonMappingException e) {
        return e.getPath().stream().map(ExtractionMappingReader::nameOf).collect(joining("."));
    }

    private static String nameOf(JsonMappingException.Reference reference) {
        return reference.getFieldName() == null ? String.valueOf(reference.getIndex()) : reference.getFieldName();
    }
}
