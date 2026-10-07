package org.icij.datashare.tabular;

import org.icij.datashare.json.JsonObjectMapper;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

/** A mapping authored by hand is read strictly, and every reason it is refused names the field, not a Java type. */
public class ExtractionMappingReaderTest {
    private static final String MAPPING = """
            {"id": "m1", "projectId": "prj", "name": "companies", "model": "ftm", "documentId": "docId",
             "entities": {"c": {"type": "Company", "keys": ["id"], "properties": {"name": {"columns": ["name"]}}}}}
            """;

    @Test
    public void test_read_builds_the_mapping() throws Exception {
        assertThat(read(MAPPING).entities().get("c").type()).isEqualTo("Company");
    }

    @Test
    public void test_read_refuses_a_misspelled_field_instead_of_dropping_it() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("\"documentId\": \"docId\"", "\"documentId\": \"docId\", \"rootID\": \"zip\"")));

        assertThat(malformed.getMessage()).isEqualTo("unknown field 'rootID'");
    }

    @Test
    public void test_read_names_the_path_of_a_misspelled_nested_field() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("{\"columns\": [\"name\"]}", "{\"columns\": [\"name\"], \"dateformat\": \"yyyy\"}")));

        assertThat(malformed.getMessage()).isEqualTo("unknown field 'entities.c.properties.name.dateformat'");
    }

    @Test
    public void test_read_names_a_missing_field() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("\"name\": \"companies\", ", "")));

        assertThat(malformed.getMessage()).isEqualTo("missing field 'name'");
    }

    @Test
    public void test_read_names_a_field_of_the_wrong_type() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replaceFirst("\"entities\": \\{.*}", "\"entities\": \"none\"}")));

        assertThat(malformed.getMessage()).isEqualTo("field 'entities' has the wrong type");
    }

    @Test
    public void test_read_keeps_the_reason_a_mapping_is_refused() {
        MalformedExtractionMapping malformed = assertThrows(MalformedExtractionMapping.class,
                () -> read(MAPPING.replace("{\"columns\": [\"name\"]}", "{\"columns\": [\"name\"], \"literal\": \"x\"}")));

        assertThat(malformed.getMessage()).startsWith("entities.c.properties.name: a property is filled from exactly one");
    }

    private static ExtractionMapping read(String json) throws Exception {
        return ExtractionMappingReader.read(JsonObjectMapper.readTree(json.getBytes()));
    }
}
