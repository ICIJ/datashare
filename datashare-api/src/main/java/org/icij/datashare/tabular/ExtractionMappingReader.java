package org.icij.datashare.tabular;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
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

    private ExtractionMappingReader() {}

    public static ExtractionMapping read(JsonNode node) {
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
    // pointer names the missing field, and any other refusal already explains itself.
    private static String refusalOf(ValueInstantiationException e) {
        String path = pathOf(e);
        Throwable refusal = e.getCause();
        if (refusal instanceof NullPointerException) {
            String field = path.isEmpty() ? refusal.getMessage() : path + "." + refusal.getMessage();
            return "missing field '%s'".formatted(field);
        }
        return path.isEmpty() ? refusal.getMessage() : path + ": " + refusal.getMessage();
    }

    private static String pathOf(JsonMappingException e) {
        return e.getPath().stream().map(ExtractionMappingReader::nameOf).collect(joining("."));
    }

    private static String nameOf(JsonMappingException.Reference reference) {
        return reference.getFieldName() == null ? String.valueOf(reference.getIndex()) : reference.getFieldName();
    }
}
